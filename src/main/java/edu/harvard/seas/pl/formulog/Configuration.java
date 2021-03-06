package edu.harvard.seas.pl.formulog;

/*-
 * #%L
 * FormuLog
 * %%
 * Copyright (C) 2018 - 2019 President and Fellows of Harvard College
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import edu.harvard.seas.pl.formulog.ast.Rule;
import edu.harvard.seas.pl.formulog.db.IndexedFactDb;
import edu.harvard.seas.pl.formulog.smt.SmtStrategy;
import edu.harvard.seas.pl.formulog.symbols.FunctionSymbol;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.util.Pair;
import edu.harvard.seas.pl.formulog.util.Util;

public final class Configuration {

	private Configuration() {
		throw new AssertionError("impossible");
	}

	public static final boolean recordFuncDiagnostics = propIsSet("timeFuncs");
	private static final Map<FunctionSymbol, AtomicLong> funcTimes = new ConcurrentHashMap<>();

	public static final boolean recordRuleDiagnostics = propIsSet("timeRules");
	private static final Map<Rule<?, ?>, Pair<AtomicLong, AtomicLong>> ruleTimes = new ConcurrentHashMap<>();

	public static final boolean debugSmt = propIsSet("debugSmt");

	public static final boolean timeSmt = propIsSet("timeSmt");
	private static final AtomicLong smtEvalTime = new AtomicLong();
	private static final AtomicLong smtDeclTime = new AtomicLong();
	private static final AtomicLong smtInferTime = new AtomicLong();
	private static final AtomicLong smtSerialTime = new AtomicLong();
	private static final AtomicLong smtWaitTime = new AtomicLong();

	public static final boolean printRelSizes = propIsSet("printRelSizes");
	public static final boolean printFinalRules = propIsSet("printFinalRules");
	public static final boolean debugRounds = propIsSet("debugRounds");

	public static final int optimizationSetting = getIntProp("optimize", 0);

	public static final int taskSize = getIntProp("taskSize", 128);
	public static final int smtTaskSize = getIntProp("smtTaskSize", 8);
	public static final int smtCacheSize = getIntProp("smtCacheSize", 100);
	public static final SmtStrategy smtStrategy = getSmtStrategy();

	public static final int parallelism = getIntProp("parallelism", 4);

	public static final boolean useDemandTransformation = propIsSet("useDemandTransformation", true);

	public static final List<String> trackedRelations = getListProp("trackedRelations");

	public static final List<String> factDirs = getListProp("factDirs");

	public static final boolean debugMst = propIsSet("debugMst");

	public static final PrintPreference printResultsPreference = getPrintResultsPreference();

	public static final boolean codeGen = propIsSet("codeGen");
	public static final boolean testCodeGen = propIsSet("testCodeGen");

	public static final String souffleInclude = System.getProperty("souffleInclude");
	public static final String boostInclude = System.getProperty("boostInclude");
	public static final String antlrInclude = System.getProperty("antlrInclude");
	public static final String boostLib = System.getProperty("boostLib");
	public static final String outputExec = System.getProperty("outputExec");

	public static final int memoizeThreshold() {
		return getIntProp("memoizeThreshold", 0);
	}

	public static final boolean genComparators = propIsSet("genComparators", true);

	public static final boolean inlineInRules = propIsSet("inlineInRules", true);

	public static final boolean eagerSemiNaive = propIsSet("eagerSemiNaive");

	static {
		if (recordFuncDiagnostics) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					printFuncDiagnostics(System.err);
				}

			});
		}
		if (recordRuleDiagnostics) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					printRuleDiagnostics(System.err);
				}

			});
		}
		if (timeSmt) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					printSmtDiagnostics(System.err);
				}

			});
		}
		Runtime.getRuntime().addShutdownHook(new Thread() {

			@Override
			public void run() {
				printConfiguration(System.err);
			}

		});
	}

	public static synchronized void printConfiguration(PrintStream out) {
		// out.println("[CONFIG] noResults=" + noResults);
		// out.println("[CONFIG] timeRules=" + recordRuleDiagnostics);
		// out.println("[CONFIG] timeFuncs=" + recordFuncDiagnostics);
		// out.println("[CONFIG] timeSmt=" + timeSmt);
		// out.println("[CONFIG] optimize=" + optimizationSetting);
		// out.println("[CONFIG] taskSize=" + taskSize);
		// out.println("[CONFIG] smtTaskSize=" + smtTaskSize);
		// out.println("[CONFIG] memoizeThreshold=" + memoizeThreshold());
		// out.println("[CONFIG] noModel=" + noModel());
	}

	public static void recordSmtDeclTime(long time) {
		smtDeclTime.addAndGet(time);
	}

	public static void recordSmtInferTime(long time) {
		smtInferTime.addAndGet(time);
	}

	public static void recordSmtSerialTime(long time) {
		smtSerialTime.addAndGet(time);
	}

	public static void recordSmtEvalTime(long time) {
		smtEvalTime.addAndGet(time);
	}

	public static void recordSmtWaitTime(long time) {
		smtWaitTime.addAndGet(time);
	}

	public static synchronized void printSmtDiagnostics(PrintStream out) {
		out.println("[SMT DECL TIME] " + smtDeclTime.get() + "ms");
		out.println("[SMT INFER TIME] " + smtInferTime.get() + "ms");
		out.println("[SMT SERIAL TIME] " + smtSerialTime.get() + "ms");
		out.println("[SMT EVAL TIME] " + smtEvalTime.get() + "ms");
		out.println("[SMT WAIT TIME] " + smtWaitTime.get() + "ms");
	}

	public static void recordFuncTime(FunctionSymbol func, long time) {
		AtomicLong l = Util.lookupOrCreate(funcTimes, func, () -> new AtomicLong());
		l.addAndGet(time);
	}

	public static Map<FunctionSymbol, AtomicLong> getFuncDiagnostics() {
		return Collections.unmodifiableMap(funcTimes);
	}

	public static synchronized void printFuncDiagnostics(PrintStream out) {
		Map<FunctionSymbol, AtomicLong> times = Configuration.getFuncDiagnostics();
		List<Map.Entry<FunctionSymbol, AtomicLong>> sorted = times.entrySet().stream().sorted(sortTimes)
				.collect(Collectors.toList());
		Iterator<Map.Entry<FunctionSymbol, AtomicLong>> it = sorted.iterator();
		int end = Math.min(times.size(), 10);
		for (int i = 0; i < end; ++i) {
			Map.Entry<FunctionSymbol, AtomicLong> e = it.next();
			out.println("[FUNC DIAGNOSTICS] " + e.getValue().get() + "ms: " + e.getKey());
		}
	}

	private static final Comparator<Map.Entry<?, AtomicLong>> sortTimes = new Comparator<Map.Entry<?, AtomicLong>>() {

		@Override
		public int compare(Entry<?, AtomicLong> e1, Entry<?, AtomicLong> e2) {
			return -Long.compare(e1.getValue().get(), e2.getValue().get());
		}

	};	
	
	private static final Comparator<Map.Entry<?, Pair<AtomicLong, AtomicLong>>> sortPairedTimes = new Comparator<Map.Entry<?, Pair<AtomicLong, AtomicLong>>>() {

		@Override
		public int compare(Entry<?, Pair<AtomicLong, AtomicLong>> e1, Entry<?, Pair<AtomicLong, AtomicLong>> e2) {
			return -Long.compare(getTotal(e1), getTotal(e2));
		}

		private long getTotal(Entry<?, Pair<AtomicLong, AtomicLong>> e) {
			Pair<AtomicLong, AtomicLong> p = e.getValue();
			return p.fst().get() + p.snd().get();
		}

	};

	public static void recordRulePrefixTime(Rule<?, ?> rule, long time) {
		Pair<AtomicLong, AtomicLong> p = Util.lookupOrCreate(ruleTimes, rule,
				() -> new Pair<>(new AtomicLong(), new AtomicLong()));
		p.fst().addAndGet(time);
	}

	public static void recordRuleSuffixTime(Rule<?, ?> rule, long time) {
		Pair<AtomicLong, AtomicLong> p = Util.lookupOrCreate(ruleTimes, rule,
				() -> new Pair<>(new AtomicLong(), new AtomicLong()));
		p.snd().addAndGet(time);
	}

	public static synchronized void printRuleDiagnostics(PrintStream out) {
		Map<Rule<?, ?>, Pair<AtomicLong, AtomicLong>> times = ruleTimes;
		List<Map.Entry<Rule<?, ?>, Pair<AtomicLong, AtomicLong>>> sorted = times.entrySet().stream().sorted(sortPairedTimes)
				.collect(Collectors.toList());
		Iterator<Map.Entry<Rule<?, ?>, Pair<AtomicLong, AtomicLong>>> it = sorted.iterator();
		int end = Math.min(times.size(), 10);
		for (int i = 0; i < end; ++i) {
			Map.Entry<Rule<?, ?>, Pair<AtomicLong, AtomicLong>> e = it.next();
			Pair<AtomicLong, AtomicLong> p = e.getValue();
			long pre = p.fst().get();
			long suf = p.snd().get();
			long total = pre + suf;
			out.println("[RULE DIAGNOSTICS] " + total + " (" + pre + " + " + suf + ") ms:\n" + e.getKey());
		}
	}

	public static synchronized void printRelSizes(PrintStream out, String header, IndexedFactDb db,
			boolean printEmpty) {
		for (RelationSymbol sym : db.getSymbols()) {
			if (printEmpty || !db.isEmpty(sym)) {
				out.println("[" + header + "] " + sym + ": " + db.countDistinct(sym) + " / " + db.numIndices(sym)
						+ " / " + db.countDuplicates(sym));
			}
		}
	}

	private static boolean propIsSet(String prop, boolean defaultValue) {
		String val = System.getProperty(prop);
		if (val == null) {
			return defaultValue;
		}
		if (val.equals("true") || val.equals("")) {
			return true;
		}
		if (val.equals("false")) {
			return false;
		}
		throw new IllegalArgumentException("Unexpected argument for property " + prop + ": " + val);
	}

	private static boolean propIsSet(String prop) {
		return propIsSet(prop, false);
	}

	private static int getIntProp(String prop, int def) {
		String val = System.getProperty(prop);
		if (val == null) {
			return def;
		}
		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Property " + prop + " expects an integer argument");
		}
	}

	private static List<String> getListProp(String prop) {
		String val = System.getProperty(prop);
		if (val == null || val.equals("")) {
			return Collections.emptyList();
		}
		List<String> l = new ArrayList<>();
		breakIntoCollection(val, l);
		return Collections.unmodifiableList(l);
	}

	private static void breakIntoCollection(String s, Collection<String> acc) {
		int split;
		while ((split = s.indexOf(',')) != -1) {
			String sub = s.substring(0, split);
			acc.add(sub);
			if (split == s.length()) {
				return;
			}
			s = s.substring(split + 1);
		}
		acc.add(s);
	}

	private static SmtStrategy getSmtStrategy() {
		String val = System.getProperty("smtStrategy");
		if (val == null) {
			val = "queue-1";
		}
		if (val.equals("perThreadPushPop")) {
			return new SmtStrategy(SmtStrategy.Tag.PER_THREAD_PUSH_POP, null);
		}
		if (val.equals("perThreadNaive")) {
			return new SmtStrategy(SmtStrategy.Tag.PER_THREAD_NAIVE, null);
		}
		Pattern p = Pattern.compile("queue-(\\d+)");
		Matcher m = p.matcher(val);
		if (m.matches()) {
			int size = Integer.parseInt(m.group(1));
			return new SmtStrategy(SmtStrategy.Tag.QUEUE, size);
		}
		p = Pattern.compile("bestMatch-(\\d+)");
		m = p.matcher(val);
		if (m.matches()) {
			int size = Integer.parseInt(m.group(1));
			return new SmtStrategy(SmtStrategy.Tag.BEST_MATCH, size);
		}
		p = Pattern.compile("perThreadQueue-(\\d+)");
		m = p.matcher(val);
		if (m.matches()) {
			int size = Integer.parseInt(m.group(1));
			return new SmtStrategy(SmtStrategy.Tag.PER_THREAD_QUEUE, size);
		}
		p = Pattern.compile("perThreadBestMatch-(\\d+)");
		m = p.matcher(val);
		if (m.matches()) {
			int size = Integer.parseInt(m.group(1));
			return new SmtStrategy(SmtStrategy.Tag.PER_THREAD_BEST_MATCH, size);
		}
		throw new IllegalArgumentException("Unrecognized SMT strategy: " + val);
	}

	private static Set<String> selectedRelsToPrint;

	public static Set<String> getSelectedRelsToPrint() {
		assert printResultsPreference.equals(PrintPreference.SOME);
		return selectedRelsToPrint;
	}

	private static PrintPreference getPrintResultsPreference() {
		String val = System.getProperty("printResults");
		if (val == null) {
			val = "all";
		}
		if (val.startsWith("some:")) {
			selectedRelsToPrint = new HashSet<>();
			breakIntoCollection(val.substring(5), selectedRelsToPrint);
			return PrintPreference.SOME;
		}
		switch (val) {
		case "all":
			return PrintPreference.ALL;
		case "none":
			return PrintPreference.NONE;
		case "edb":
			return PrintPreference.EDB;
		case "idb":
			return PrintPreference.IDB;
		case "query":
			return PrintPreference.QUERY;
		default:
			throw new IllegalArgumentException("Unrecognized print result preference: " + val);
		}
	}

}
