/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.streaming.runtime.operators.windowing;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ReducingStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.TypeInfoParser;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.PassThroughWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.RichWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.evictors.CountEvictor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.ContinuousEventTimeTrigger;
import org.apache.flink.streaming.api.windowing.triggers.CountTrigger;
import org.apache.flink.streaming.api.windowing.triggers.EventTimeTrigger;
import org.apache.flink.streaming.api.windowing.triggers.PurgingTrigger;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.streaming.runtime.operators.windowing.functions.InternalIterableWindowFunction;
import org.apache.flink.streaming.runtime.operators.windowing.functions.InternalSingleValueWindowFunction;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.StreamTaskState;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.TestHarnessUtil;
import org.apache.flink.util.Collector;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

public class WindowOperatorTest {

	// For counting if close() is called the correct number of times on the SumReducer
	private static AtomicInteger closeCalled = new AtomicInteger(0);

	private void testSlidingEventTimeWindows(OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness) throws Exception {

		long initialTime = 0L;

		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3999));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 20));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1998));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1999));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));


		testHarness.processWatermark(new Watermark(initialTime + 999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 999));
		expectedOutput.add(new Watermark(999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());


		testHarness.processWatermark(new Watermark(initialTime + 1999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 1999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 3), initialTime + 1999));
		expectedOutput.add(new Watermark(1999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 2999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 2999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 3), initialTime + 2999));
		expectedOutput.add(new Watermark(2999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processWatermark(new Watermark(initialTime + 3999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 5), initialTime + 3999));
		expectedOutput.add(new Watermark(3999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 4999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 4999));
		expectedOutput.add(new Watermark(4999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 5999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 5999));
		expectedOutput.add(new Watermark(5999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());


		// those don't have any effect...
		testHarness.processWatermark(new Watermark(initialTime + 6999));
		testHarness.processWatermark(new Watermark(initialTime + 7999));
		expectedOutput.add(new Watermark(6999));
		expectedOutput.add(new Watermark(7999));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testSlidingEventTimeWindowsReduce() throws Exception {
		closeCalled.set(0);

		final int WINDOW_SIZE = 3;
		final int WINDOW_SLIDE = 1;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
				new SumReducer(),
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator = new WindowOperator<>(
				SlidingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS), Time.of(WINDOW_SLIDE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(inputType, new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.setup();
		testHarness.open();

		testSlidingEventTimeWindows(testHarness);

		testHarness.close();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testSlidingEventTimeWindowsApply() throws Exception {
		closeCalled.set(0);

		final int WINDOW_SIZE = 3;
		final int WINDOW_SLIDE = 1;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ListStateDescriptor<Tuple2<String, Integer>> stateDesc = new ListStateDescriptor<>("window-contents",
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Iterable<Tuple2<String, Integer>>, Tuple2<String, Integer>, TimeWindow> operator = new WindowOperator<>(
				SlidingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS), Time.of(WINDOW_SLIDE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalIterableWindowFunction<>(new RichSumReducer<TimeWindow>()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(inputType, new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		testSlidingEventTimeWindows(testHarness);

		testHarness.close();

		// we close once in the rest...
		Assert.assertEquals("Close was not called.", 2, closeCalled.get());
	}

	private void testTumblingEventTimeWindows(OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness) throws Exception {
		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3999));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 20));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1998));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1999));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));


		testHarness.processWatermark(new Watermark(initialTime + 999));
		expectedOutput.add(new Watermark(999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());


		testHarness.processWatermark(new Watermark(initialTime + 1999));
		expectedOutput.add(new Watermark(1999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processWatermark(new Watermark(initialTime + 2999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 2999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 3), initialTime + 2999));
		expectedOutput.add(new Watermark(2999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 3999));
		expectedOutput.add(new Watermark(3999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 4999));
		expectedOutput.add(new Watermark(4999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 5999));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 5999));
		expectedOutput.add(new Watermark(5999));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());


		// those don't have any effect...
		testHarness.processWatermark(new Watermark(initialTime + 6999));
		testHarness.processWatermark(new Watermark(initialTime + 7999));
		expectedOutput.add(new Watermark(6999));
		expectedOutput.add(new Watermark(7999));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testTumblingEventTimeWindowsReduce() throws Exception {
		closeCalled.set(0);

		final int WINDOW_SIZE = 3;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
				new SumReducer(),
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator = new WindowOperator<>(
				TumblingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		testTumblingEventTimeWindows(testHarness);

		testHarness.close();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testTumblingEventTimeWindowsApply() throws Exception {
		closeCalled.set(0);

		final int WINDOW_SIZE = 3;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ListStateDescriptor<Tuple2<String, Integer>> stateDesc = new ListStateDescriptor<>("window-contents",
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Iterable<Tuple2<String, Integer>>, Tuple2<String, Integer>, TimeWindow> operator = new WindowOperator<>(
				TumblingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalIterableWindowFunction<>(new RichSumReducer<TimeWindow>()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		testTumblingEventTimeWindows(testHarness);

		testHarness.close();

		// we close once in the rest...
		Assert.assertEquals("Close was not called.", 2, closeCalled.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testSessionWindows() throws Exception {
		closeCalled.set(0);

		final int SESSION_SIZE = 3;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ListStateDescriptor<Tuple2<String, Integer>> stateDesc = new ListStateDescriptor<>("window-contents",
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Iterable<Tuple2<String, Integer>>, Tuple3<String, Long, Long>, TimeWindow> operator = new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(SESSION_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalIterableWindowFunction<>(new SessionWindowFunction()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 0));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 3), initialTime + 2500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 10));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 1000));

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 2500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 4), initialTime + 5501));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 5), initialTime + 6000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 5), initialTime + 6000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 6), initialTime + 6050));

		testHarness.processWatermark(new Watermark(initialTime + 12000));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key1-6", 10L, 5500L), initialTime + 5499));
		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-6", 0L, 5500L), initialTime + 5499));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-20", 5501L, 9050L), initialTime + 9049));
		expectedOutput.add(new Watermark(initialTime + 12000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 10), initialTime + 15000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 20), initialTime + 15000));

		testHarness.processWatermark(new Watermark(initialTime + 17999));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-30", 15000L, 18000L), initialTime + 17999));
		expectedOutput.add(new Watermark(initialTime + 17999));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple3ResultSortComparator());

		testHarness.close();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testReduceSessionWindows() throws Exception {
		closeCalled.set(0);

		final int SESSION_SIZE = 3;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>(
				"window-contents", new SumReducer(), inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator = new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(SESSION_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 0));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 3), initialTime + 2500));

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 10));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 2500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 4), initialTime + 5501));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 5), initialTime + 6000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 5), initialTime + 6000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 6), initialTime + 6050));

		testHarness.processWatermark(new Watermark(initialTime + 12000));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key1-6", 10L, 5500L), initialTime + 5499));
		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-6", 0L, 5500L), initialTime + 5499));
		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-20", 5501L, 9050L), initialTime + 9049));
		expectedOutput.add(new Watermark(initialTime + 12000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 10), initialTime + 15000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 20), initialTime + 15000));

		testHarness.processWatermark(new Watermark(initialTime + 17999));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-30", 15000L, 18000L), initialTime + 17999));
		expectedOutput.add(new Watermark(initialTime + 17999));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple3ResultSortComparator());

		testHarness.close();
	}

	/**
	 * This tests whether merging works correctly with the CountTrigger.
	 * @throws Exception
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testSessionWindowsWithCountTrigger() throws Exception {
		closeCalled.set(0);

		final int SESSION_SIZE = 3;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ListStateDescriptor<Tuple2<String, Integer>> stateDesc = new ListStateDescriptor<>("window-contents",
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Iterable<Tuple2<String, Integer>>, Tuple3<String, Long, Long>, TimeWindow> operator = new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(SESSION_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalIterableWindowFunction<>(new SessionWindowFunction()),
				PurgingTrigger.of(CountTrigger.of(4)),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 0));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 3), initialTime + 2500));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 4), initialTime + 3500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 10));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 1000));

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 2500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 6000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 6500));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 3), initialTime + 7000));


		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-10", 0L, 6500L), initialTime + 6499));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple3ResultSortComparator());

		// add an element that merges the two "key1" sessions, they should now have count 6, and therfore fire
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 10), initialTime + 4500));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key1-22", 10L, 10000L), initialTime + 9999L));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple3ResultSortComparator());

		testHarness.close();
	}

	@Test
	public void testMergeAndEvictor() throws Exception {
		// verify that merging WindowAssigner and Evictor cannot be used together

		StreamExecutionEnvironment env = LocalStreamEnvironment.createLocalEnvironment();

		WindowedStream<String, String, TimeWindow> windowedStream = env.fromElements("Hello", "Ciao")
				.keyBy(new KeySelector<String, String>() {
					@Override
					public String getKey(String value) throws Exception {
						return value;
					}
				})
				.window(EventTimeSessionWindows.withGap(Time.seconds(5)));

		try {
			windowedStream.evictor(CountEvictor.of(13));

		} catch (UnsupportedOperationException e) {
			// expected
			// use a catch to ensure that the exception is thrown by the fold
			return;
		}

		fail("The evictor call should fail.");

		env.execute();

	}

	@Test
	@SuppressWarnings("unchecked")
	/**
	 * This tests a custom Session window assigner that assigns some elements to "point windows",
	 * windows that have the same timestamp for start and end.
	 *
	 * <p> In this test, elements that have 33 as the second tuple field will be put into a point
	 * window.
	 */
	public void testPointSessions() throws Exception {
		closeCalled.set(0);

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ListStateDescriptor<Tuple2<String, Integer>> stateDesc = new ListStateDescriptor<>("window-contents",
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Iterable<Tuple2<String, Integer>>, Tuple3<String, Long, Long>, TimeWindow> operator = new WindowOperator<>(
				new PointSessionWindows(3000),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalIterableWindowFunction<>(new SessionWindowFunction()),
				EventTimeTrigger.create(),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 0));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 33), initialTime + 1000));

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 33), initialTime + 2500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 10));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 33), initialTime + 2500));

		testHarness.processWatermark(new Watermark(initialTime + 12000));

		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key1-36", 10L, 4000L), initialTime + 3999));
		expectedOutput.add(new StreamRecord<>(new Tuple3<>("key2-67", 0L, 3000L), initialTime + 2999));
		expectedOutput.add(new Watermark(initialTime + 12000));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple3ResultSortComparator());

		testHarness.close();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testContinuousWatermarkTrigger() throws Exception {
		closeCalled.set(0);

		final int WINDOW_SIZE = 3;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
				new SumReducer(),
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, GlobalWindow> operator = new WindowOperator<>(
				GlobalWindows.create(),
				new GlobalWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, GlobalWindow, Tuple2<String, Integer>>()),
				ContinuousEventTimeTrigger.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS)),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// The global window actually ignores these timestamps...

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime));

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 20));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1998));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1999));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));


		testHarness.processWatermark(new Watermark(initialTime + 1000));
		expectedOutput.add(new Watermark(1000));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());


		testHarness.processWatermark(new Watermark(initialTime + 2000));
		expectedOutput.add(new Watermark(2000));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 3000));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 3), Long.MAX_VALUE));
		expectedOutput.add(new Watermark(3000));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 4000));
		expectedOutput.add(new Watermark(4000));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 5000));
		expectedOutput.add(new Watermark(5000));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processWatermark(new Watermark(initialTime + 6000));

 		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 3), Long.MAX_VALUE));

		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 5), Long.MAX_VALUE));
		expectedOutput.add(new Watermark(6000));
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());


		// those don't have any effect...
		testHarness.processWatermark(new Watermark(initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 8000));
		expectedOutput.add(new Watermark(7000));
		expectedOutput.add(new Watermark(8000));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.close();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testCountTrigger() throws Exception {
		closeCalled.set(0);

		final int WINDOW_SIZE = 4;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
				new SumReducer(),
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, GlobalWindow> operator = new WindowOperator<>(
				GlobalWindows.create(),
				new GlobalWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, GlobalWindow, Tuple2<String, Integer>>()),
				PurgingTrigger.of(CountTrigger.of(WINDOW_SIZE)),
				0);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse(
				"Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

		testHarness.open();

		// The global window actually ignores these timestamps...

		// add elements out-of-order
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 20));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1998));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1999));

		// do a snapshot, close and restore again
		StreamTaskState snapshot = testHarness.snapshot(0L, 0L);
		testHarness.close();
		testHarness.setup();
		testHarness.restore(snapshot, 10L);
		testHarness.open();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));


		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 4), Long.MAX_VALUE));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 10999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));

		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key1", 4), Long.MAX_VALUE));
		expectedOutput.add(new StreamRecord<>(new Tuple2<>("key2", 4), Long.MAX_VALUE));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expectedOutput, testHarness.getOutput(), new Tuple2ResultSortComparator());

		testHarness.close();
	}

	@Test
	public void testRestoreAndSnapshotAreInSync() throws Exception {

		final int WINDOW_SIZE = 3;
		final int WINDOW_SLIDE = 1;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
				new SumReducer(),
				inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator = new WindowOperator<>(
				SlidingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS), Time.of(WINDOW_SLIDE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				0);


		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
				new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		operator.setInputType(inputType, new ExecutionConfig());
		testHarness.open();

		WindowOperator.Timer<String, TimeWindow> timer1 = new WindowOperator.Timer<>(1L, "key1", new TimeWindow(1L, 2L));
		WindowOperator.Timer<String, TimeWindow> timer2 = new WindowOperator.Timer<>(3L, "key1", new TimeWindow(1L, 2L));
		WindowOperator.Timer<String, TimeWindow> timer3 = new WindowOperator.Timer<>(2L, "key1", new TimeWindow(1L, 2L));
		operator.processingTimeTimers.add(timer1);
		operator.processingTimeTimers.add(timer2);
		operator.processingTimeTimers.add(timer3);
		operator.processingTimeTimersQueue.add(timer1);
		operator.processingTimeTimersQueue.add(timer2);
		operator.processingTimeTimersQueue.add(timer3);

		operator.processingTimeTimerTimestamps.add(1L, 10);
		operator.processingTimeTimerTimestamps.add(2L, 5);
		operator.processingTimeTimerTimestamps.add(3L, 1);


		StreamTaskState snapshot = testHarness.snapshot(0, 0);

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> otherOperator = new WindowOperator<>(
				SlidingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS), Time.of(WINDOW_SLIDE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				0);

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> otherTestHarness =
				new OneInputStreamOperatorTestHarness<>(otherOperator);

		otherTestHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);
		otherOperator.setInputType(inputType, new ExecutionConfig());

		otherTestHarness.setup();
		otherTestHarness.restore(snapshot, 0);
		otherTestHarness.open();

		Assert.assertEquals(operator.processingTimeTimers, otherOperator.processingTimeTimers);
		Assert.assertArrayEquals(operator.processingTimeTimersQueue.toArray(), otherOperator.processingTimeTimersQueue.toArray());
		Assert.assertEquals(operator.processingTimeTimerTimestamps, otherOperator.processingTimeTimerTimestamps);
	}

	@Test
	public void testLateness() throws Exception {
		final int WINDOW_SIZE = 2;
		final long LATENESS = 500;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator =
			new WindowOperator<>(
				TumblingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				LATENESS);

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		operator.setInputType(inputType, new ExecutionConfig());
		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 500));
		testHarness.processWatermark(new Watermark(initialTime + 1500));

		expected.add(new Watermark(initialTime + 1500));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1300));
		testHarness.processWatermark(new Watermark(initialTime + 2300));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 1999));
		expected.add(new Watermark(initialTime + 2300));

		// this will not be dropped because window.maxTimestamp() + allowedLateness > currentWatermark
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1997));
		testHarness.processWatermark(new Watermark(initialTime + 6000));

		// this is 1 and not 3 because the trigger fires and purges
		expected.add(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1999));
		expected.add(new Watermark(initialTime + 6000));

		// this will be dropped because window.maxTimestamp() + allowedLateness < currentWatermark
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1998));
		testHarness.processWatermark(new Watermark(initialTime + 7000));

		expected.add(new Watermark(initialTime + 7000));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, testHarness.getOutput(), new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testCleanupTimeOverflow() throws Exception {
		final int WINDOW_SIZE = 1;
		final long LATENESS = 2000;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator =
			new WindowOperator<>(
				TumblingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				LATENESS);

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		operator.setInputType(inputType, new ExecutionConfig());
		testHarness.open();

		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		long timestamp = Long.MAX_VALUE - 1750;
		long windowSize = WINDOW_SIZE * 1000;
		long endOfWindow = (timestamp - (timestamp % windowSize)) + windowSize - 1;

		// normal element
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), Long.MAX_VALUE - 1750));
		testHarness.processWatermark(new Watermark(Long.MAX_VALUE - 1500));
		testHarness.processWatermark(new Watermark(endOfWindow));

		// guarantee that the cleanup would overflow
		Assert.assertTrue(endOfWindow + LATENESS < endOfWindow);

		// guarantee that the cleanup would have happened if it were not for the overflow check
		Assert.assertTrue(endOfWindow + LATENESS < Long.MAX_VALUE - 1500);
		Assert.assertTrue(Long.MAX_VALUE - 1500 < endOfWindow);
		Assert.assertTrue(endOfWindow < Long.MAX_VALUE);

		expected.add(new Watermark(Long.MAX_VALUE - 1500));
		expected.add(new StreamRecord<>(new Tuple2<>("key2", 1), endOfWindow));
		expected.add(new Watermark(endOfWindow));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, testHarness.getOutput(), new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessTumbling() throws Exception {
		final int WINDOW_SIZE = 2;
		final long LATENESS = 0;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator =
			new WindowOperator<>(
				TumblingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				LATENESS);

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		operator.setInputType(inputType, new ExecutionConfig());
		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		// normal element
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1985));

		expected.add(new Watermark(initialTime + 1985));

		// a late that is added to an active window
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1980));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 1999));
		expected.add(new Watermark(initialTime + 1999));

		// dropped as late
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1998));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2001));
		testHarness.processWatermark(new Watermark(initialTime + 2999));

		expected.add(new Watermark(initialTime + 2999));

		testHarness.processWatermark(new Watermark(initialTime + 3999));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3999));
		expected.add(new Watermark(initialTime + 3999));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, testHarness.getOutput(), new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSliding() throws Exception {
		final int WINDOW_SIZE = 3;
		final int WINDOW_SLIDE = 1;
		final long LATENESS = 0;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple2<String, Integer>, TimeWindow> operator =
			new WindowOperator<>(
				SlidingEventTimeWindows.of(Time.of(WINDOW_SIZE, TimeUnit.SECONDS), Time.of(WINDOW_SLIDE, TimeUnit.SECONDS)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new PassThroughWindowFunction<String, TimeWindow, Tuple2<String, Integer>>()),
				EventTimeTrigger.create(),
				LATENESS);

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple2<String, Integer>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		operator.setInputType(inputType, new ExecutionConfig());
		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1999));
		expected.add(new Watermark(initialTime + 1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 3000));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 2), initialTime + 2999));
		expected.add(new Watermark(initialTime + 3000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 3001));

		// lateness is set to 0 and window_size = 3 sec and slide 1, the following 2 elements (2400) are late,
		// and assigned to windows ending at 2999, 3999, 4999.
		// The 2999 is dropped because it is already inactive (WM = 2999) but the rest are kept.

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2400));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2400));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 3001));
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 3900));
		testHarness.processWatermark(new Watermark(initialTime + 6000));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 5), initialTime + 3999));
		expected.add(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 3999));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 4), initialTime + 4999));
		expected.add(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 4999));

		expected.add(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 5999));
		expected.add(new StreamRecord<>(new Tuple2<>("key1", 2), initialTime + 5999));

		expected.add(new Watermark(initialTime + 6000));

		// dropped due to lateness
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key1", 1), initialTime + 3001));

		testHarness.processWatermark(new Watermark(initialTime + 25000));

		expected.add(new Watermark(initialTime + 25000));

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, testHarness.getOutput(), new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSessionZeroLateness() throws Exception {
		final int GAP_SIZE = 3;
		final long LATENESS = 0;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator =
			new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(GAP_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				EventTimeTrigger.create(),
				LATENESS);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new Watermark(1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 4998));

		expected.add(new Watermark(4998));

		// late but added
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 4500));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 8500));
		testHarness.processWatermark(new Watermark(initialTime + 7400));

		expected.add(new Watermark(7400));

		// late element to merge the previous sessions
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 11501));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-5", 1000l, 11500l), 11499));
		expected.add(new Watermark(11501));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 11600));
		testHarness.processWatermark(new Watermark(initialTime + 14600));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 11600l, 14600l), 14599));
		expected.add(new Watermark(14600));

		// NON-ACCUM TRIGGER
		// with 0 lateness this is dropped because it is assigned to a non-active window,								(testDropDueToLatenessSessionZeroLateness)
		// with a small lateness (e.g. 10), this is assigned to window 10000-13000 (the 11600 is GCed after firing) 	(testDropDueToLatenessSessionWithLateness)
		// finally with a big lateness (e.g. 10000) the first window is not GCed so this merges all the windows so far 	(testDropDueToLatenessSessionWithHugeLateness)

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 10000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 14500));
		testHarness.processWatermark(new Watermark(initialTime + 20000));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 14500l, 17500l), 17499));
		expected.add(new Watermark(20000));

		testHarness.processWatermark(new Watermark(initialTime + 100000));

		expected.add(new Watermark(100000));

		ConcurrentLinkedQueue<Object> actual = testHarness.getOutput();

		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, actual, new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSessionZeroLatenessAccum() throws Exception {

		final int GAP_SIZE = 3;
		final long LATENESS = 0;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator =
			new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(GAP_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				new EventTimeTriggerAccum(),
				LATENESS);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new Watermark(1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 4998));

		expected.add(new Watermark(4998));

		// late but added
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 4500));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 8500));
		testHarness.processWatermark(new Watermark(initialTime + 7400));

		expected.add(new Watermark(7400));

		// late element to merge the previous sessions
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 11501));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-5", 1000l, 11500l), 11499));
		expected.add(new Watermark(11501));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 11600));
		testHarness.processWatermark(new Watermark(initialTime + 14600));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 11600l, 14600l), 14599));
		expected.add(new Watermark(14600));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 10000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 14500));
		testHarness.processWatermark(new Watermark(initialTime + 20000));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 14500l, 17500l), 17499));
		expected.add(new Watermark(20000));

		testHarness.processWatermark(new Watermark(initialTime + 100000));
		expected.add(new Watermark(100000));

		ConcurrentLinkedQueue<Object> actual = testHarness.getOutput();
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, actual, new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSessionWithLateness() throws Exception {
		final int GAP_SIZE = 3;
		final long LATENESS = 10;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator =
			new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(GAP_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				EventTimeTrigger.create(),
				LATENESS);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();
		long initialTime = 0L;

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new Watermark(1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 4998));

		expected.add(new Watermark(4998));

		// late but added
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 4500));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 8500));
		testHarness.processWatermark(new Watermark(initialTime + 7400));

		expected.add(new Watermark(7400));

		// late element to merge the previous sessions
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 11501));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-5", 1000l, 11500l), 11499));
		expected.add(new Watermark(11501));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 11600));
		testHarness.processWatermark(new Watermark(initialTime + 14600));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 11600l, 14600l), 14599));
		expected.add(new Watermark(14600));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 10000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 14500));
		testHarness.processWatermark(new Watermark(initialTime + 20000));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 14500l, 17500l), 17499));
		expected.add(new Watermark(20000));

		testHarness.processWatermark(new Watermark(initialTime + 100000));
		expected.add(new Watermark(100000));

		ConcurrentLinkedQueue<Object> actual = testHarness.getOutput();
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, actual, new Tuple2ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSessionWithLatenessAccum() throws Exception {
		final int GAP_SIZE = 3;
		final long LATENESS = 10;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator =
			new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(GAP_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				new EventTimeTriggerAccum(),
				LATENESS);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new Watermark(1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 4998));

		expected.add(new Watermark(4998));

		// late but added
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 4500));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 8500));
		testHarness.processWatermark(new Watermark(initialTime + 7400));

		expected.add(new Watermark(7400));

		// late element to merge the previous sessions
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 11501));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-5", 1000l, 11500l), 11499));
		expected.add(new Watermark(11501));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 11600));
		testHarness.processWatermark(new Watermark(initialTime + 14600));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 11600l, 14600l), 14599));
		expected.add(new Watermark(14600));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 10000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 14500));
		testHarness.processWatermark(new Watermark(initialTime + 20000));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-2", 10000l, 14600l), 14599));
		expected.add(new StreamRecord<>(new Tuple3<>("key2-3", 10000l, 17500l), 17499));
		expected.add(new Watermark(20000));

		testHarness.processWatermark(new Watermark(initialTime + 100000));

		expected.add(new Watermark(100000));

		ConcurrentLinkedQueue<Object> actual = testHarness.getOutput();
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, actual, new Tuple3ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSessionWithHugeLateness() throws Exception {

		final int GAP_SIZE = 3;
		final long LATENESS = 10000;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator =
			new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(GAP_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				EventTimeTrigger.create(),
				LATENESS);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new Watermark(1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 4998));

		expected.add(new Watermark(4998));

		// late but added
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 4500));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 8500));
		testHarness.processWatermark(new Watermark(initialTime + 7400));

		expected.add(new Watermark(7400));

		// late element to merge the previous sessions
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 11501));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-5", 1000l, 11500l), 11499));
		expected.add(new Watermark(11501));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 11600));
		testHarness.processWatermark(new Watermark(initialTime + 14600));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 11600l, 14600l), 14599));
		expected.add(new Watermark(14600));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 10000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 14500));
		testHarness.processWatermark(new Watermark(initialTime + 20000));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 10000l, 13000l), 12999));
		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 14500l, 17500l), 17499));
		expected.add(new Watermark(20000));

		testHarness.processWatermark(new Watermark(initialTime + 100000));

		expected.add(new Watermark(100000));

		ConcurrentLinkedQueue<Object> actual = testHarness.getOutput();
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, actual, new Tuple3ResultSortComparator());
		testHarness.close();
	}

	@Test
	public void testDropDueToLatenessSessionWithHugeLatenessAccum() throws Exception {
		final int GAP_SIZE = 3;
		final long LATENESS = 10000;

		TypeInformation<Tuple2<String, Integer>> inputType = TypeInfoParser.parse("Tuple2<String, Integer>");

		ReducingStateDescriptor<Tuple2<String, Integer>> stateDesc = new ReducingStateDescriptor<>("window-contents",
			new SumReducer(),
			inputType.createSerializer(new ExecutionConfig()));

		WindowOperator<String, Tuple2<String, Integer>, Tuple2<String, Integer>, Tuple3<String, Long, Long>, TimeWindow> operator =
			new WindowOperator<>(
				EventTimeSessionWindows.withGap(Time.seconds(GAP_SIZE)),
				new TimeWindow.Serializer(),
				new TupleKeySelector(),
				BasicTypeInfo.STRING_TYPE_INFO.createSerializer(new ExecutionConfig()),
				stateDesc,
				new InternalSingleValueWindowFunction<>(new ReducedSessionWindowFunction()),
				new EventTimeTriggerAccum(),
				LATENESS);

		operator.setInputType(TypeInfoParser.<Tuple2<String, Integer>>parse("Tuple2<String, Integer>"), new ExecutionConfig());

		OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Tuple3<String, Long, Long>> testHarness =
			new OneInputStreamOperatorTestHarness<>(operator);

		testHarness.configureForKeyedStream(new TupleKeySelector(), BasicTypeInfo.STRING_TYPE_INFO);

		testHarness.open();

		long initialTime = 0L;
		ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 1000));
		testHarness.processWatermark(new Watermark(initialTime + 1999));

		expected.add(new Watermark(1999));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 2000));
		testHarness.processWatermark(new Watermark(initialTime + 4998));

		expected.add(new Watermark(4998));

		// late but added
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 4500));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 8500));
		testHarness.processWatermark(new Watermark(initialTime + 7400));

		expected.add(new Watermark(7400));

		// late element to merge the previous sessions
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 7000));
		testHarness.processWatermark(new Watermark(initialTime + 11501));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-5", 1000l, 11500l), 11499));
		expected.add(new Watermark(11501));

		// new session
		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 11600));
		testHarness.processWatermark(new Watermark(initialTime + 14600));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-1", 11600l, 14600l), 14599));
		expected.add(new Watermark(14600));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 10000));

		testHarness.processElement(new StreamRecord<>(new Tuple2<>("key2", 1), initialTime + 14500));
		testHarness.processWatermark(new Watermark(initialTime + 20000));

		expected.add(new StreamRecord<>(new Tuple3<>("key2-7", 1000l, 14600l), 14599));
		expected.add(new StreamRecord<>(new Tuple3<>("key2-8", 1000l, 17500l), 17499));
		expected.add(new Watermark(20000));

		testHarness.processWatermark(new Watermark(initialTime + 100000));
		expected.add(new Watermark(100000));

		ConcurrentLinkedQueue<Object> actual = testHarness.getOutput();
		TestHarnessUtil.assertOutputEqualsSorted("Output was not correct.", expected, actual, new Tuple3ResultSortComparator());
		testHarness.close();
	}

	// ------------------------------------------------------------------------
	//  UDFs
	// ------------------------------------------------------------------------

	public static class SumReducer implements ReduceFunction<Tuple2<String, Integer>> {
		private static final long serialVersionUID = 1L;
		@Override
		public Tuple2<String, Integer> reduce(Tuple2<String, Integer> value1,
				Tuple2<String, Integer> value2) throws Exception {
			return new Tuple2<>(value2.f0, value1.f1 + value2.f1);
		}
	}


	public static class RichSumReducer<W extends Window> extends RichWindowFunction<Tuple2<String, Integer>, Tuple2<String, Integer>, String, W> {
		private static final long serialVersionUID = 1L;

		private boolean openCalled = false;

		@Override
		public void open(Configuration parameters) throws Exception {
			super.open(parameters);
			openCalled = true;
		}

		@Override
		public void close() throws Exception {
			super.close();
			closeCalled.incrementAndGet();
		}

		@Override
		public void apply(String key,
				W window,
				Iterable<Tuple2<String, Integer>> input,
				Collector<Tuple2<String, Integer>> out) throws Exception {

			if (!openCalled) {
				fail("Open was not called");
			}
			int sum = 0;

			for (Tuple2<String, Integer> t: input) {
				sum += t.f1;
			}
			out.collect(new Tuple2<>(key, sum));

		}

	}

	@SuppressWarnings("unchecked")
	private static class Tuple2ResultSortComparator implements Comparator<Object> {
		@Override
		public int compare(Object o1, Object o2) {
			if (o1 instanceof Watermark || o2 instanceof Watermark) {
				return 0;
			} else {
				StreamRecord<Tuple2<String, Integer>> sr0 = (StreamRecord<Tuple2<String, Integer>>) o1;
				StreamRecord<Tuple2<String, Integer>> sr1 = (StreamRecord<Tuple2<String, Integer>>) o2;
				if (sr0.getTimestamp() != sr1.getTimestamp()) {
					return (int) (sr0.getTimestamp() - sr1.getTimestamp());
				}
				int comparison = sr0.getValue().f0.compareTo(sr1.getValue().f0);
				if (comparison != 0) {
					return comparison;
				} else {
					return sr0.getValue().f1 - sr1.getValue().f1;
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static class Tuple3ResultSortComparator implements Comparator<Object> {
		@Override
		public int compare(Object o1, Object o2) {
			if (o1 instanceof Watermark || o2 instanceof Watermark) {
				return 0;
			} else {
				StreamRecord<Tuple3<String, Long, Long>> sr0 = (StreamRecord<Tuple3<String, Long, Long>>) o1;
				StreamRecord<Tuple3<String, Long, Long>> sr1 = (StreamRecord<Tuple3<String, Long, Long>>) o2;
				if (sr0.getTimestamp() != sr1.getTimestamp()) {
					return (int) (sr0.getTimestamp() - sr1.getTimestamp());
				}
				int comparison = sr0.getValue().f0.compareTo(sr1.getValue().f0);
				if (comparison != 0) {
					return comparison;
				} else {
					comparison = (int) (sr0.getValue().f1 - sr1.getValue().f1);
					if (comparison != 0) {
						return comparison;
					}
					return (int) (sr0.getValue().f1 - sr1.getValue().f1);
				}
			}
		}
	}

	private static class TupleKeySelector implements KeySelector<Tuple2<String, Integer>, String> {
		private static final long serialVersionUID = 1L;

		@Override
		public String getKey(Tuple2<String, Integer> value) throws Exception {
			return value.f0;
		}
	}

	public static class SessionWindowFunction implements WindowFunction<Tuple2<String, Integer>, Tuple3<String, Long, Long>, String, TimeWindow> {
		private static final long serialVersionUID = 1L;

		@Override
		public void apply(String key,
				TimeWindow window,
				Iterable<Tuple2<String, Integer>> values,
				Collector<Tuple3<String, Long, Long>> out) throws Exception {
			int sum = 0;
			for (Tuple2<String, Integer> i: values) {
				sum += i.f1;
			}
			String resultString = key + "-" + sum;
			out.collect(new Tuple3<>(resultString, window.getStart(), window.getEnd()));
		}
	}

	public static class ReducedSessionWindowFunction implements WindowFunction<Tuple2<String, Integer>, Tuple3<String, Long, Long>, String, TimeWindow> {
		private static final long serialVersionUID = 1L;

		@Override
		public void apply(String key,
				TimeWindow window,
				Iterable<Tuple2<String, Integer>> values,
				Collector<Tuple3<String, Long, Long>> out) throws Exception {
			for (Tuple2<String, Integer> val: values) {
				out.collect(new Tuple3<>(key + "-" + val.f1, window.getStart(), window.getEnd()));
			}
		}
	}


	public static class PointSessionWindows extends EventTimeSessionWindows {
		private static final long serialVersionUID = 1L;


		private PointSessionWindows(long sessionTimeout) {
			super(sessionTimeout);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Collection<TimeWindow> assignWindows(Object element, long timestamp) {
			if (element instanceof Tuple2) {
				Tuple2<String, Integer> t2 = (Tuple2<String, Integer>) element;
				if (t2.f1 == 33) {
					return Collections.singletonList(new TimeWindow(timestamp, timestamp));
				}
			}
			return Collections.singletonList(new TimeWindow(timestamp, timestamp + sessionTimeout));
		}
	}

	/**
	 * A trigger that fires at the end of the window but does not
	 * purge the state of the fired window. This is to test the state
	 * garbage collection mechanism.
	 */
	public class EventTimeTriggerAccum extends Trigger<Object, TimeWindow> {
		private static final long serialVersionUID = 1L;

		private EventTimeTriggerAccum() {}

		@Override
		public TriggerResult onElement(Object element, long timestamp, TimeWindow window, TriggerContext ctx) throws Exception {
			ctx.registerEventTimeTimer(window.maxTimestamp());
			return (window.maxTimestamp() <= ctx.getCurrentWatermark()) ?
				TriggerResult.FIRE :
				TriggerResult.CONTINUE;
		}

		@Override
		public TriggerResult onEventTime(long time, TimeWindow window, TriggerContext ctx) {
			return time == window.maxTimestamp() ?
				TriggerResult.FIRE :
				TriggerResult.CONTINUE;
		}

		@Override
		public TriggerResult onProcessingTime(long time, TimeWindow window, TriggerContext ctx) throws Exception {
			return TriggerResult.CONTINUE;
		}

		@Override
		public void clear(TimeWindow window, TriggerContext ctx) throws Exception {
			ctx.deleteEventTimeTimer(window.maxTimestamp());
		}

		@Override
		public boolean canMerge() {
			return true;
		}

		@Override
		public TriggerResult onMerge(TimeWindow window,
									 OnMergeContext ctx) {
			ctx.registerEventTimeTimer(window.maxTimestamp());
			return TriggerResult.CONTINUE;
		}

		@Override
		public String toString() {
			return "EventTimeTrigger()";
		}
	}
}
