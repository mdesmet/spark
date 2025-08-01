/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.streaming

import java.time.Duration

import org.apache.spark.sql.Encoders
import org.apache.spark.sql.execution.streaming.{ListStateImplWithTTL, MemoryStream}
import org.apache.spark.sql.execution.streaming.state.RocksDBStateStoreProvider
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.streaming.util.StreamManualClock
import org.apache.spark.tags.SlowSQLTest

// MultiStatefulVariableTTLProcessor is a StatefulProcessor that consumes a stream of
// strings and returns a stream of <string, count> pairs.
//
// Internally, it uses several stateful variables to store the count of each string, for
// the sole purpose of verifying that these stateful variables all stay in sync and do not
// interfere with each other.
//
// The pattern of calling appendValue is to simulate the old behavior of appendValue, which
// used to add a record into the secondary index for every appendList call.
class MultiStatefulVariableTTLProcessor(ttlConfig: TTLConfig)
  extends StatefulProcessor[String, String, (String, Long)]{
  @transient private var _listState: ListState[String] = _
  // Map from index to count
  @transient private var _mapState: MapState[Long, Long] = _
  // Counts the number of times the string has occurred. It should always be
  // equal to the size of the list state at the start and end of handleInputRows.
  @transient private var _valueState: ValueState[Long] = _

  override def init(
      outputMode: OutputMode,
      timeMode: TimeMode): Unit = {
    _listState = getHandle
      .getListState("listState", Encoders.STRING, ttlConfig)
    _mapState = getHandle
        .getMapState("mapState", Encoders.scalaLong, Encoders.scalaLong, ttlConfig)
    _valueState = getHandle
        .getValueState("valueState", Encoders.scalaLong, ttlConfig)
  }
  override def handleInputRows(
      key: String,
      inputRows: Iterator[String],
      timerValues: TimerValues): Iterator[(String, Long)] = {
    assertSanity()
    val iter = inputRows.map { row =>
      // Update the list state
      _listState.appendValue(row)

      // Update the map state
      val mapStateCurrentSize = _mapState.iterator().size
      _mapState.updateValue(mapStateCurrentSize + 1, mapStateCurrentSize + 1)

      // Update the value state
      val currentCountFromValueState = _valueState.get()
      _valueState.update(currentCountFromValueState + 1)

      assertSanity()

      (key, _listState.get().size.toLong)
    }

    iter
  }

  // Asserts that the list state, map state, and value state are all in sync.
  private def assertSanity(): Unit = {
    val listSize = _listState.get().size
    val mapSize = _mapState.iterator().size
    val valueState = _valueState.get()
    assert(listSize == mapSize)
    assert(listSize == valueState)
  }
}

class ListStateTTLProcessor(ttlConfig: TTLConfig)
  extends StatefulProcessor[String, InputEvent, OutputEvent] {

  @transient private var _listState: ListState[Int] = _

  override def init(
      outputMode: OutputMode,
      timeMode: TimeMode): Unit = {
    _listState = getHandle
      .getListState("listState", Encoders.scalaInt, ttlConfig)
  }

  override def handleInputRows(
      key: String,
      inputRows: Iterator[InputEvent],
      timerValues: TimerValues): Iterator[OutputEvent] = {
    var results = List[OutputEvent]()

    inputRows.foreach { row =>
      val resultIter = processRow(row,
        _listState.asInstanceOf[ListStateImplWithTTL[Int]])
      resultIter.foreach { r =>
        results = r :: results
      }
    }

    results.iterator
  }

  private def processRow(
      row: InputEvent,
      listState: ListStateImplWithTTL[Int]): Iterator[OutputEvent] = {

    var results = List[OutputEvent]()
    val key = row.key
    if (row.action == "get") {
      val currState = listState.get()
      currState.foreach { v =>
        results = OutputEvent(key, v, isTTLValue = false, -1) :: results
      }
    } else if (row.action == "get_without_enforcing_ttl") {
      val currState = listState.getWithoutEnforcingTTL()
      currState.foreach { v =>
        results = OutputEvent(key, v, isTTLValue = false, -1) :: results
      }
    } else if (row.action == "get_ttl_value_from_state") {
      val ttlValues = listState.getTTLValues()
      ttlValues.foreach { ttlValue =>
        results = OutputEvent(key, ttlValue._1, isTTLValue = true, ttlValue._2) :: results
      }
    } else if (row.action == "put") {
      listState.put(Array(row.value))
    } else if (row.action == "append") {
      listState.appendValue(row.value)
    } else if (row.action == "get_values_in_ttl_state") {
      val ttlValues = listState.getValueInTTLState()
      ttlValues.foreach { v =>
        results = OutputEvent(key, -1, isTTLValue = true, ttlValue = v) :: results
      }
    } else if (row.action == "get_values_in_min_state") {
      val minValues = listState.getMinValues()
      minValues.foreach { minExpirationMs =>
        results = OutputEvent(key, -1, isTTLValue = true, ttlValue = minExpirationMs) :: results
      }
    } else if (row.action == "clear") {
      listState.clear()
    }

    results.iterator
  }
}

/**
 * Test suite for testing list state with TTL.
 * We use the base TTL suite with a list state processor.
 */
@SlowSQLTest
class TransformWithListStateTTLSuite extends TransformWithStateTTLTest
  with StateStoreMetricsTest {

  import testImplicits._

  override def getProcessor(ttlConfig: TTLConfig):
    StatefulProcessor[String, InputEvent, OutputEvent] = {
      new ListStateTTLProcessor(ttlConfig)
  }

  override def getStateTTLMetricName: String = "numListStateWithTTLVars"

  test("verify the list state secondary index has at most one record per key") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName,
      SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
      val ttlConfig = TTLConfig(ttlDuration = Duration.ofMinutes(10))
      val inputStream = MemoryStream[String]
        val result = inputStream.toDS()
          .groupByKey(x => x)
          .transformWithState(
            new MultiStatefulVariableTTLProcessor(ttlConfig),
            TimeMode.ProcessingTime(),
            OutputMode.Append())
      val clock = new StreamManualClock

      testStream(result)(
        StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock),

        // We want all of the inputs to have different timestamps, so that each record
        // gets its own unique TTL, and thus, its own unique secondary index record. Each
        // is also processed in its own microbatch to ensure a unique batchTimestampMs.
        AddData(inputStream, "k1"),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(("k1", 1)),

        AddData(inputStream, "k2"),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(("k2", 1)),

        AddData(inputStream, "k1"),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(("k1", 2)),

        AddData(inputStream, "k2"),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(("k2", 2)),

        AddData(inputStream, "k1"),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(("k1", 3)),

        AddData(inputStream, "k2"),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(("k2", 3)),

        // For each unique key that occurs t times, the MultiStatefulVariableTTLProcessor maintains:
        //    - Map state: t records in the primary, and t records in the TTL index
        //    - List state: 1 record in the primary, TTL, min, and count indexes
        //    - Value state: 1 record in the primary, and 1 record in the TTL index
        //
        // So in total, that amounts to 2t + 4 + 2 = 2t + 6 records. This is for internal and
        // non-internal column families. For non-internal column families, the total records are
        // t + 2.
        //
        // In this test, we have 2 unique keys, and each key occurs 3 times. Thus, the total number
        // of keys in state is 2 * (2t + 6) where t = 3, which is 24. And the total number of
        // records in the primary indexes are 2 * (t + 2) = 10.
        //
        // The number of updated rows is the total across the last time assertNumStateRows
        // was called, and we only update numRowsUpdated for primary key updates. We ran 6 batches
        // and each wrote 3 primary keys, so the total number of updated rows is 6 * 3 = 18.
        assertNumStateRows(total = 10, updated = 18)
      )
    }
  }

  test("verify iterator works with expired values in beginning of list") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName,
      SQLConf.SHUFFLE_PARTITIONS.key -> "1") {

      val ttlConfig = TTLConfig(ttlDuration = Duration.ofMinutes(1))
      val inputStream = MemoryStream[InputEvent]
      val result = inputStream.toDS()
        .groupByKey(x => x.key)
        .transformWithState(
          getProcessor(ttlConfig),
          TimeMode.ProcessingTime(),
          OutputMode.Append())
      val clock = new StreamManualClock
      testStream(result)(
        StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock),
        AddData(inputStream, InputEvent("k1", "put", 1)),
        AdvanceManualClock(1 * 1000),
        AddData(inputStream,
          InputEvent("k1", "append", 2),
          InputEvent("k1", "append", 3)
        ),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(),
        // get ttl values
        AddData(inputStream, InputEvent("k1", "get_ttl_value_from_state", -1, null)),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(
          OutputEvent("k1", 1, isTTLValue = true, 61000),
          OutputEvent("k1", 2, isTTLValue = true, 62000),
          OutputEvent("k1", 3, isTTLValue = true, 62000)
        ),
        // advance clock to add elements with later TTL
        AdvanceManualClock(45 * 1000), // batch timestamp: 48000
        AddData(inputStream,
          InputEvent("k1", "append", 4),
          InputEvent("k1", "append", 5),
          InputEvent("k1", "append", 6)
        ),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(),
        Execute { q =>
          assert(q.lastProgress.stateOperators(0).numRowsUpdated === 3)
        },
        // get ttl values
        AddData(inputStream, InputEvent("k1", "get_ttl_value_from_state", -1, null)),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(
          OutputEvent("k1", 1, isTTLValue = true, 61000),
          OutputEvent("k1", 2, isTTLValue = true, 62000),
          OutputEvent("k1", 3, isTTLValue = true, 62000),
          OutputEvent("k1", 4, isTTLValue = true, 109000),
          OutputEvent("k1", 5, isTTLValue = true, 109000),
          OutputEvent("k1", 6, isTTLValue = true, 109000)
        ),
        AddData(inputStream, InputEvent("k1", "get", -1, null)),
        // advance clock to expire the first three elements
        AdvanceManualClock(15 * 1000), // batch timestamp: 65000
        CheckNewAnswer(
          OutputEvent("k1", 4, isTTLValue = false, -1),
          OutputEvent("k1", 5, isTTLValue = false, -1),
          OutputEvent("k1", 6, isTTLValue = false, -1)
        ),
        Execute { q =>
          assert(q.lastProgress.stateOperators(0).numRowsRemoved === 3)
        },
        // ensure that expired elements are no longer in state
        AddData(inputStream, InputEvent("k1", "get_without_enforcing_ttl", -1, null)),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(
          OutputEvent("k1", 4, isTTLValue = false, -1),
          OutputEvent("k1", 5, isTTLValue = false, -1),
          OutputEvent("k1", 6, isTTLValue = false, -1)
        ),
        AddData(inputStream, InputEvent("k1", "get_values_in_ttl_state", -1, null)),
        AdvanceManualClock(1 * 1000),
        CheckNewAnswer(
          OutputEvent("k1", -1, isTTLValue = true, 109000)
        )
      )
    }
  }

  // We can only change the TTL of a state variable upon query restart.
  // Therefore, only on query restart, will elements not be stored in
  // ascending order of TTL.
  // The following test cases will test the case where the elements are not stored in
  // ascending order of TTL by stopping the query, setting the new TTL, and restarting
  // the query to check that the expired elements in the middle or end of the list
  // are not returned.
  test("verify iterator works with expired values in middle of list") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName,
      SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
      withTempDir { checkpointLocation =>
        // starting the query with a TTL of 3 minutes
        val ttlConfig1 = TTLConfig(ttlDuration = Duration.ofMinutes(3))
        val inputStream = MemoryStream[InputEvent]
        val result1 = inputStream.toDS()
          .groupByKey(x => x.key)
          .transformWithState(
            getProcessor(ttlConfig1),
            TimeMode.ProcessingTime(),
            OutputMode.Append())

        val clock = new StreamManualClock
        // add 3 elements with a duration of 3 minutes
        // batch timestamp at the end of this block will be 4000
        testStream(result1)(
          StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock,
            checkpointLocation = checkpointLocation.getAbsolutePath),
          AddData(inputStream, InputEvent("k1", "put", 1)),
          AdvanceManualClock(1 * 1000),
          AddData(inputStream, InputEvent("k1", "append", 2)),
          AddData(inputStream, InputEvent("k1", "append", 3)),
          // advance clock to trigger processing
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          // get ttl values
          AddData(inputStream, InputEvent("k1", "get_ttl_value_from_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = true, 181000),
            OutputEvent("k1", 2, isTTLValue = true, 182000),
            OutputEvent("k1", 3, isTTLValue = true, 182000)
          ),

          AddData(inputStream, InputEvent("k1", "get", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1)
          ),
          StopStream
        )

        // Here, we are restarting the query with a new TTL of 15 seconds
        // so that we can add elements to the middle of the list that will
        // expire quickly
        // batch timestamp at the end of this block will be 7000
        val ttlConfig2 = TTLConfig(ttlDuration = Duration.ofSeconds(15))
        val result2 = inputStream.toDS()
          .groupByKey(x => x.key)
          .transformWithState(
            getProcessor(ttlConfig2),
            TimeMode.ProcessingTime(),
            OutputMode.Append())
        // add 3 elements with a duration of 15 seconds
        testStream(result2)(
          StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock,
            checkpointLocation = checkpointLocation.getAbsolutePath),
          AddData(inputStream, InputEvent("k1", "append", 4)),
          AddData(inputStream, InputEvent("k1", "append", 5)),
          AddData(inputStream, InputEvent("k1", "append", 6)),
          // advance clock to trigger processing
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          // get all elements without enforcing ttl
          AddData(inputStream, InputEvent("k1", "get_without_enforcing_ttl", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1),
            OutputEvent("k1", 4, isTTLValue = false, -1),
            OutputEvent("k1", 5, isTTLValue = false, -1),
            OutputEvent("k1", 6, isTTLValue = false, -1)
          ),

          AddData(inputStream, InputEvent("k1", "get_ttl_value_from_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = true, 181000),
            OutputEvent("k1", 2, isTTLValue = true, 182000),
            OutputEvent("k1", 3, isTTLValue = true, 182000),
            OutputEvent("k1", 4, isTTLValue = true, 20000),
            OutputEvent("k1", 5, isTTLValue = true, 20000),
            OutputEvent("k1", 6, isTTLValue = true, 20000)
          ),
          StopStream
        )

        // Restart the stream with the first TTL config to add elements to the end
        // with a TTL of 3 minutes
        testStream(result1)(
          StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock,
            checkpointLocation = checkpointLocation.getAbsolutePath),
          AddData(inputStream, InputEvent("k1", "append", 7)),
          AddData(inputStream, InputEvent("k1", "append", 8)),
          AddData(inputStream, InputEvent("k1", "append", 9)),
          // advance clock to trigger processing
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          // advance clock to expire the middle three elements
          AddData(inputStream, InputEvent("k1", "get_values_in_ttl_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", -1, isTTLValue = true, 20000)
          ),

          // progress batch timestamp from 9000 to 54000, expiring the middle
          // three elements.
          AdvanceManualClock(45 * 1000),
          // Get all elements in the list
          AddData(inputStream, InputEvent("k1", "get", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1),
            OutputEvent("k1", 7, isTTLValue = false, -1),
            OutputEvent("k1", 8, isTTLValue = false, -1),
            OutputEvent("k1", 9, isTTLValue = false, -1)
          ),

          AddData(inputStream, InputEvent("k1", "get_without_enforcing_ttl", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1),
            OutputEvent("k1", 7, isTTLValue = false, -1),
            OutputEvent("k1", 8, isTTLValue = false, -1),
            OutputEvent("k1", 9, isTTLValue = false, -1)
          ),

          AddData(inputStream, InputEvent("k1", "get_values_in_ttl_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", -1, isTTLValue = true, 181000)
          ),
          StopStream
        )
      }
    }
  }

  // If we have a list for a key k1 -> [(v1, t1), (v2, t2), (v3, t3)] and they _all_ expire,
  // then there should be no remaining records in any primary (or secondary index) for that key.
  // However, if we have a separate key k2 -> [(v1, t4)] and the time is less than t4, then it
  // should still be present after the clearing for k1.
  test("verify min-expiry index doesn't insert when the new minimum is None") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName,
      SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
      withTempDir { checkpointLocation =>
        val inputStream = MemoryStream[InputEvent]
        val ttlConfig1 = TTLConfig(ttlDuration = Duration.ofMinutes(1))
        val result1 = inputStream
          .toDS()
          .groupByKey(x => x.key)
          .transformWithState(
            getProcessor(ttlConfig1),
            TimeMode.ProcessingTime(),
            OutputMode.Append()
          )

        val clock = new StreamManualClock
        testStream(result1)(
          StartStream(
            Trigger.ProcessingTime("1 second"),
            triggerClock = clock,
            checkpointLocation = checkpointLocation.getAbsolutePath
          ),

          // Add 3 elements all with different eviction timestamps.
          AddData(inputStream, InputEvent("k1", "append", 1)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          AddData(inputStream, InputEvent("k1", "append", 2)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          AddData(inputStream, InputEvent("k1", "append", 3)),
          AdvanceManualClock(1 * 1000), // Time is 3000
          CheckNewAnswer(),

          // Add a separate key; this should not be affected by k1 expiring.
          // It will have an expiration of 64000.
          AddData(inputStream, InputEvent("k2", "put", 1)),

          // Now, we should have: k1 -> [1, 2, 3] with TTLs [61000, 62000, 63000] respectively
          AddData(inputStream, InputEvent("k1", "get_ttl_value_from_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer( // Time is 4000 for this micro-batch
            OutputEvent("k1", 1, isTTLValue = true, 61000),
            OutputEvent("k1", 2, isTTLValue = true, 62000),
            OutputEvent("k1", 3, isTTLValue = true, 63000)
          ),

          AddData(inputStream, InputEvent("k1", "get_values_in_min_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer( // Time is 5000 for this micro-batch
            OutputEvent("k1", -1, isTTLValue = true, 61000)
          ),

          // The k1 records expire at 63000, and the current time is 5000. So, we advance the
          // clock by 63 - 5 = 58 seconds to expire those.
          AdvanceManualClock((63 - 5) * 1000),
          CheckNewAnswer(),

          // There should be 4 state rows left over: the primary, TTL, min-expiry, and count
          // indexes for k2.
          //
          // It's important to check with assertNumStateRows, since the InputEvents
          // only return values for the current grouping key, not the entirety of RocksDB.
          assertNumStateRows(total = 1, updated = 4),

          // The k1 calls should both return no values. However, the k2 calls should return
          // one record each. We put these into one AddData call since we want them all to
          // run when the batchTimestampMs is 65000.
          AddData(inputStream,
            // These should both return no values, since all of k1 has been expired.
            InputEvent("k1", "get_values_in_ttl_state", -1, null),
            InputEvent("k1", "get_values_in_min_state", -1, null),

            // However, k2 still has a record.
            InputEvent("k2", "get_values_in_ttl_state", -1, null),
            InputEvent("k2", "get_values_in_min_state", -1, null)
          ),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer( // Time is 65000 for this micro-batch
            OutputEvent("k2", -1, isTTLValue = true, 64000),
            OutputEvent("k2", -1, isTTLValue = true, 64000)
          ),

          assertNumStateRows(total = 0, updated = 0),

          StopStream
        )
      }
    }
  }

  test("verify iterator works with expired values in end of list") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName,
      SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
      withTempDir { checkpointLocation =>
        // first TTL config to start the query with a TTL of 2 minutes
        val inputStream = MemoryStream[InputEvent]
        val ttlConfig1 = TTLConfig(ttlDuration = Duration.ofMinutes(2))
        val result1 = inputStream.toDS()
          .groupByKey(x => x.key)
          .transformWithState(
            getProcessor(ttlConfig1),
            TimeMode.ProcessingTime(),
            OutputMode.Append())

        // second TTL config we will use to start the query with a TTL of 1 minute
        val ttlConfig2 = TTLConfig(ttlDuration = Duration.ofMinutes(1))
        val result2 = inputStream.toDS()
          .groupByKey(x => x.key)
          .transformWithState(
            getProcessor(ttlConfig2),
            TimeMode.ProcessingTime(),
            OutputMode.Append())

        val clock = new StreamManualClock
        // add 3 elements with a duration of a minute
        // expected batch timestamp at the end of the stream is 4000
        testStream(result1)(
          StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock,
            checkpointLocation = checkpointLocation.getAbsolutePath),
          AddData(inputStream, InputEvent("k1", "put", 1)),
          AdvanceManualClock(1 * 1000),
          AddData(inputStream, InputEvent("k1", "append", 2)),
          AddData(inputStream, InputEvent("k1", "append", 3)),
          // advance clock to trigger processing
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          // get ttl values
          AddData(inputStream,
            InputEvent("k1", "get_ttl_value_from_state", -1, null),
            InputEvent("k1", "get_values_in_min_state", -1)
          ),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            // From the get_ttl_value_from_state call
            OutputEvent("k1", 1, isTTLValue = true, 121000),
            OutputEvent("k1", 2, isTTLValue = true, 122000),
            OutputEvent("k1", 3, isTTLValue = true, 122000),

            // From the get_values_in_min_state call
            OutputEvent("k1", -1, isTTLValue = true, 121000)
          ),

          AddData(inputStream, InputEvent("k1", "get", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1)
          ),
          StopStream
        )

        // Here, we are restarting the query with a new TTL of 1 minutes
        // so that the elements at the end will expire before the beginning
        // batch timestamp at the end of this block will be 7000
        testStream(result2)(
          StartStream(Trigger.ProcessingTime("1 second"), triggerClock = clock,
            checkpointLocation = checkpointLocation.getAbsolutePath),
          AddData(inputStream, InputEvent("k1", "append", 4)),
          AddData(inputStream, InputEvent("k1", "append", 5)),
          AddData(inputStream, InputEvent("k1", "append", 6)),
          // advance clock to trigger processing
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(),

          // get ttl values
          AddData(inputStream, InputEvent("k1", "get_ttl_value_from_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = true, 121000),
            OutputEvent("k1", 2, isTTLValue = true, 122000),
            OutputEvent("k1", 3, isTTLValue = true, 122000),
            OutputEvent("k1", 4, isTTLValue = true, 65000),
            OutputEvent("k1", 5, isTTLValue = true, 65000),
            OutputEvent("k1", 6, isTTLValue = true, 65000)
          ),
          AddData(inputStream, InputEvent("k1", "get_values_in_ttl_state", -1, null)),
          AdvanceManualClock(1 * 1000),

          CheckNewAnswer(
            OutputEvent("k1", -1, isTTLValue = true, 65000)
          ),
          // expire end values, batch timestamp from 7000 to 67000
          AdvanceManualClock(60 * 1000),
          AddData(inputStream, InputEvent("k1", "get", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1)
          ),
          AddData(inputStream, InputEvent("k1", "get_without_enforcing_ttl", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", 1, isTTLValue = false, -1),
            OutputEvent("k1", 2, isTTLValue = false, -1),
            OutputEvent("k1", 3, isTTLValue = false, -1)
          ),
          AddData(inputStream, InputEvent("k1", "get_values_in_ttl_state", -1, null)),
          AdvanceManualClock(1 * 1000),
          CheckNewAnswer(
            OutputEvent("k1", -1, isTTLValue = true, 121000)
          ),
          StopStream
        )
      }
    }
  }
}
