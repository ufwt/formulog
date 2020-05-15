package formulog.eval;

/*-
 * #%L
 * Formulog
 * %%
 * Copyright (C) 2018 - 2020 Anonymous Institute
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



import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import formulog.Configuration;
import formulog.ast.BasicProgram;
import formulog.ast.BasicRule;
import formulog.ast.ComplexLiteral;
import formulog.ast.Rule;
import formulog.ast.Term;
import formulog.ast.Terms;
import formulog.ast.UnificationPredicate;
import formulog.ast.UserPredicate;
import formulog.ast.Var;
import formulog.ast.ComplexLiterals.ComplexLiteralVisitor;
import formulog.db.IndexedFactDbBuilder;
import formulog.db.SortedIndexedFactDb;
import formulog.db.SortedIndexedFactDb.SortedIndexedFactDbBuilder;
import formulog.eval.SemiNaiveRule.DeltaSymbol;
import formulog.magic.MagicSetTransformer;
import formulog.smt.BestMatchSmtManager;
import formulog.smt.NaiveSmtManager;
import formulog.smt.NotThreadSafeQueueSmtManager;
import formulog.smt.PerThreadSmtManager;
import formulog.smt.PushPopSmtManager;
import formulog.smt.QueueSmtManager;
import formulog.smt.SmtManager;
import formulog.smt.SmtStrategy;
import formulog.symbols.RelationSymbol;
import formulog.symbols.Symbol;
import formulog.symbols.SymbolManager;
import formulog.types.WellTypedProgram;
import formulog.unification.SimpleSubstitution;
import formulog.util.AbstractFJPTask;
import formulog.util.CountingFJP;
import formulog.util.CountingFJPImpl;
import formulog.util.MockCountingFJP;
import formulog.util.Util;
import formulog.validating.FunctionDefValidation;
import formulog.validating.InvalidProgramException;
import formulog.validating.Stratifier;
import formulog.validating.Stratum;
import formulog.validating.ValidRule;
import formulog.validating.ast.SimpleRule;

public class SemiNaiveEvaluation implements Evaluation {

	private final SortedIndexedFactDb db;
	private final SortedIndexedFactDb deltaDb;
	private final SortedIndexedFactDb nextDeltaDb;
	private final List<Stratum> strata;
	private final UserPredicate query;
	private final CountingFJP exec;
	private final Set<RelationSymbol> trackedRelations;
	private final WellTypedProgram inputProgram;
	private final Map<RelationSymbol, Set<IndexedRule>> rules;
	private final boolean eagerEval;

	static final boolean sequential = System.getProperty("sequential") != null;
	static final boolean debugRounds = Configuration.debugRounds;

	@SuppressWarnings("serial")
	public static SemiNaiveEvaluation setup(WellTypedProgram prog, int parallelism, boolean eagerEval)
			throws InvalidProgramException {
		FunctionDefValidation.validate(prog);
		MagicSetTransformer mst = new MagicSetTransformer(prog);
		BasicProgram magicProg = mst.transform(Configuration.useDemandTransformation, true);
		Set<RelationSymbol> allRelations = new HashSet<>(magicProg.getFactSymbols());
		allRelations.addAll(magicProg.getRuleSymbols());
		SortedIndexedFactDbBuilder dbb = new SortedIndexedFactDbBuilder(allRelations);
		SortedIndexedFactDbBuilder deltaDbb = new SortedIndexedFactDbBuilder(magicProg.getRuleSymbols());
		PredicateFunctionSetter predFuncs = new PredicateFunctionSetter(
				magicProg.getFunctionCallFactory().getDefManager(), dbb);

		Map<RelationSymbol, Set<IndexedRule>> rules = new HashMap<>();
		List<Stratum> strata = new Stratifier(magicProg).stratify();
		for (Stratum stratum : strata) {
			if (stratum.hasRecursiveNegationOrAggregation()) {
				throw new InvalidProgramException("Cannot handle recursive negation or aggregation: " + stratum);
			}
			Set<RelationSymbol> stratumSymbols = stratum.getPredicateSyms();
			for (RelationSymbol sym : stratumSymbols) {
				Set<IndexedRule> rs = new HashSet<>();
				for (BasicRule br : magicProg.getRules(sym)) {
					for (SemiNaiveRule snr : SemiNaiveRule.make(br, stratumSymbols)) {
						BiFunction<ComplexLiteral, Set<Var>, Integer> score = chooseScoringFunction(eagerEval);
						ValidRule vr = ValidRule.make(tweakRule(snr, eagerEval), score);
						checkRule(vr, eagerEval);
						predFuncs.preprocess(vr);
						SimpleRule sr = SimpleRule.make(vr);
						IndexedRule ir = IndexedRule.make(sr, p -> {
							RelationSymbol psym = p.getSymbol();
							if (psym instanceof DeltaSymbol) {
								psym = ((DeltaSymbol) psym).getBaseSymbol();
								return deltaDbb.makeIndex(psym, p.getBindingPattern());
							} else {
								return dbb.makeIndex(psym, p.getBindingPattern());
							}
						});
						rs.add(ir);
						if (Configuration.printFinalRules) {
							System.err.println("[FINAL RULE]:\n" + ir);
						}
					}
				}
				rules.put(sym, rs);
			}
		}
		SortedIndexedFactDb db = dbb.build();
		predFuncs.setDb(db);

		SmtManager smt = getSmtManager();
		try {
			smt.initialize(magicProg);
		} catch (EvaluationException e) {
			throw new InvalidProgramException("Problem initializing SMT shims: " + e.getMessage());
		}
		prog.getFunctionCallFactory().getDefManager().loadBuiltInFunctions(smt);

		CountingFJP exec;
		if (sequential) {
			exec = new MockCountingFJP();
		} else {
			exec = new CountingFJPImpl(parallelism);
		}

		for (RelationSymbol sym : magicProg.getFactSymbols()) {
			for (Iterable<Term[]> tups : Util.splitIterable(magicProg.getFacts(sym), Configuration.taskSize)) {
				exec.externallyAddTask(new AbstractFJPTask(exec) {

					@Override
					public void doTask() throws EvaluationException {
						for (Term[] tup : tups) {
							try {
								db.add(sym, Terms.normalize(tup, new SimpleSubstitution()));
							} catch (EvaluationException e) {
								UserPredicate p = UserPredicate.make(sym, tup, false);
								throw new EvaluationException("Cannot normalize fact " + p + ":\n" + e.getMessage());
							}
						}
					}

				});
			}
		}
		exec.blockUntilFinished();
		if (exec.hasFailed()) {
			exec.shutdown();
			throw new InvalidProgramException(exec.getFailureCause());
		}
		return new SemiNaiveEvaluation(prog, db, deltaDbb, rules, magicProg.getQuery(), strata, exec,
				getTrackedRelations(magicProg.getSymbolManager()), eagerEval);
	}

	private static Rule<UserPredicate, ComplexLiteral> tweakRule(Rule<UserPredicate, ComplexLiteral> r,
			boolean eagerEval) {
		if (!eagerEval) {
			return r;
		}
		List<ComplexLiteral> newBody = new ArrayList<>();
		for (ComplexLiteral l : r) {
			l.accept(new ComplexLiteralVisitor<Void, Void>() {

				@Override
				public Void visit(UnificationPredicate unificationPredicate, Void input) {
					newBody.add(unificationPredicate);
					return null;
				}

				@Override
				public Void visit(UserPredicate userPredicate, Void input) {
					RelationSymbol sym = userPredicate.getSymbol();
					if (sym instanceof DeltaSymbol) {
						Term[] args = userPredicate.getArgs();
						Term[] newArgs = new Term[args.length];
						for (int i = 0; i < args.length; ++i) {
							Term arg = args[i];
							if (arg.containsUnevaluatedTerm()) {
								Var x = Var.fresh();
								newBody.add(UnificationPredicate.make(x, arg, false));
								arg = x;
							}
							newArgs[i] = arg;
						}
						newBody.add(UserPredicate.make(sym, newArgs, false));
					} else {
						newBody.add(userPredicate);
					}
					return null;
				}

			}, null);
		}
		return BasicRule.make(r.getHead(), newBody);
	}

	private static void checkRule(ValidRule r, boolean eagerEval) throws InvalidProgramException {
		if (!eagerEval) {
			return;
		}
		boolean seenUserPred = false;
		for (ComplexLiteral l : r) {
			if (l instanceof UserPredicate) {
				UserPredicate pred = (UserPredicate) l;
				if (seenUserPred && pred.getSymbol() instanceof DeltaSymbol) {
					throw new InvalidProgramException("Delta symbol could not be placed first:\n" + r);
				}
				seenUserPred = true;
			}
		}
	}

	private static SmtManager getSmtManager() {
		SmtStrategy strategy = Configuration.smtStrategy;
		switch (strategy.getTag()) {
		case QUEUE: {
			int size = (int) strategy.getMetadata();
			return new QueueSmtManager(size);
		}
		case BEST_MATCH: {
			int size = (int) strategy.getMetadata();
			return new BestMatchSmtManager(size);
		}
		case PER_THREAD_QUEUE: {
			int size = (int) strategy.getMetadata();
			return new PerThreadSmtManager(() -> new NotThreadSafeQueueSmtManager(size));
		}
		case PER_THREAD_BEST_MATCH: {
			int size = (int) strategy.getMetadata();
			return new PerThreadSmtManager(() -> new BestMatchSmtManager(size));
		}
		case PER_THREAD_PUSH_POP: {
			return new PerThreadSmtManager(() -> new PushPopSmtManager());
		}
		case PER_THREAD_NAIVE: {
			return new PerThreadSmtManager(() -> new NaiveSmtManager());
		}
		default:
			throw new UnsupportedOperationException("Cannot support SMT strategy: " + strategy);
		}
	}

	static Set<RelationSymbol> getTrackedRelations(SymbolManager sm) {
		Set<RelationSymbol> s = new HashSet<>();
		for (String name : Configuration.trackedRelations) {
			if (sm.hasName(name)) {
				Symbol sym = sm.lookupSymbol(name);
				if (sym instanceof RelationSymbol) {
					s.add((RelationSymbol) sm.lookupSymbol(name));
				} else {
					System.err.println("[WARNING] Cannot track non-relation " + sym);
				}

			} else {
				System.err.println("[WARNING] Cannot track unrecognized relation " + name);
			}
		}
		return s;
	}

	static BiFunction<ComplexLiteral, Set<Var>, Integer> chooseScoringFunction(boolean eagerEval) {
		if (eagerEval) {
			return SemiNaiveEvaluation::score4;
		}
		switch (Configuration.optimizationSetting) {
		case 0:
			return SemiNaiveEvaluation::score0;
		case 1:
			return SemiNaiveEvaluation::score1;
		case 2:
			return SemiNaiveEvaluation::score2;
		case 3:
			return SemiNaiveEvaluation::score3;
		case 4:
			return SemiNaiveEvaluation::score4;
		default:
			throw new IllegalArgumentException(
					"Unrecognized optimization setting: " + Configuration.optimizationSetting);
		}
	}

	static int score0(ComplexLiteral l, Set<Var> boundVars) {
		return 0;
	}

	static int score1(ComplexLiteral l, Set<Var> boundVars) {
		// This seems to be worse than just doing nothing.
		return l.accept(new ComplexLiteralVisitor<Void, Integer>() {

			@Override
			public Integer visit(UnificationPredicate unificationPredicate, Void input) {
				return Integer.MAX_VALUE;
			}

			@Override
			public Integer visit(UserPredicate pred, Void input) {
				if (pred.isNegated()) {
					return Integer.MAX_VALUE;
				}
				if (pred.getSymbol() instanceof DeltaSymbol) {
					return 100;
				}
				Term[] args = pred.getArgs();
				if (args.length == 0) {
					return 150;
				}
				int bound = 0;
				for (int i = 0; i < args.length; ++i) {
					if (boundVars.containsAll(args[i].varSet())) {
						bound++;
					}
				}
				double percentBound = ((double) bound) / args.length * 100;
				return (int) percentBound;
			}

		}, null);
	}

	static int score2(ComplexLiteral l, Set<Var> boundVars) {
		return l.accept(new ComplexLiteralVisitor<Void, Integer>() {

			@Override
			public Integer visit(UnificationPredicate unificationPredicate, Void input) {
				return Integer.MAX_VALUE;
			}

			@Override
			public Integer visit(UserPredicate pred, Void input) {
				Term[] args = pred.getArgs();
				if (args.length == 0) {
					return 150;
				}
				int bound = 0;
				for (int i = 0; i < args.length; ++i) {
					if (boundVars.containsAll(args[i].varSet())) {
						bound++;
					}
				}
				double percentBound = ((double) bound) / args.length * 100;
				return (int) percentBound;
			}

		}, null);
	}

	static int score3(ComplexLiteral l, Set<Var> boundVars) {
		return l.accept(new ComplexLiteralVisitor<Void, Integer>() {

			@Override
			public Integer visit(UnificationPredicate unificationPredicate, Void input) {
				return Integer.MAX_VALUE;
			}

			@Override
			public Integer visit(UserPredicate pred, Void input) {
				if (pred.isNegated()) {
					return Integer.MAX_VALUE;
				}
				if (pred.getSymbol() instanceof DeltaSymbol) {
					return 125;
				}
				Term[] args = pred.getArgs();
				if (args.length == 0) {
					return Integer.MAX_VALUE;
				}
				int bound = 0;
				for (int i = 0; i < args.length; ++i) {
					if (boundVars.containsAll(args[i].varSet())) {
						bound++;
					}
				}
				double percentBound = ((double) bound) / args.length * 100;
				return (int) percentBound;
			}

		}, null);
	}

	static int score4(ComplexLiteral l, Set<Var> boundVars) {
		return l.accept(new ComplexLiteralVisitor<Void, Integer>() {

			@Override
			public Integer visit(UnificationPredicate unificationPredicate, Void input) {
				return Integer.MAX_VALUE;
			}

			@Override
			public Integer visit(UserPredicate pred, Void input) {
				if (pred.isNegated() || pred.getSymbol() instanceof DeltaSymbol) {
					return Integer.MAX_VALUE;
				}
				return 0;
			}

		}, null);
	}

	SemiNaiveEvaluation(WellTypedProgram inputProgram, SortedIndexedFactDb db,
			IndexedFactDbBuilder<SortedIndexedFactDb> deltaDbb, Map<RelationSymbol, Set<IndexedRule>> rules,
			UserPredicate query, List<Stratum> strata, CountingFJP exec, Set<RelationSymbol> trackedRelations,
			boolean eagerEval) {
		this.inputProgram = inputProgram;
		this.db = db;
		this.query = query;
		this.strata = strata;
		this.exec = exec;
		this.trackedRelations = trackedRelations;
		this.deltaDb = deltaDbb.build();
		this.nextDeltaDb = deltaDbb.build();
		this.rules = rules;
		this.eagerEval = eagerEval;
	}

	@Override
	public WellTypedProgram getInputProgram() {
		return inputProgram;
	}

	public List<Stratum> getStrata() {
		return strata;
	}

	@Override
	public synchronized void run() throws EvaluationException {
		if (Configuration.printRelSizes) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					Configuration.printRelSizes(System.err, "REL SIZE", db, true);
				}

			});
		}
		for (Stratum stratum : strata) {
			evaluateStratum(stratum);
		}
	}

	private void evaluateStratum(Stratum stratum) throws EvaluationException {
		List<IndexedRule> l = new ArrayList<>();
		for (RelationSymbol sym : stratum.getPredicateSyms()) {
			l.addAll(rules.get(sym));
		}
		if (eagerEval) {
			new EagerStratumEvaluator(stratum.getRank(), db, l, exec, trackedRelations).evaluate();
		} else {
			new RoundBasedStratumEvaluator(stratum.getRank(), db, deltaDb, nextDeltaDb, l, exec, trackedRelations)
					.evaluate();
		}
	}

	public Set<IndexedRule> getRules(RelationSymbol sym) {
		return Collections.unmodifiableSet(rules.get(sym));
	}

	@Override
	public synchronized EvaluationResult getResult() {
		return new EvaluationResult() {

			@Override
			public Iterable<UserPredicate> getAll(RelationSymbol sym) {
				if (!db.getSymbols().contains(sym)) {
					throw new IllegalArgumentException("Unrecognized relation symbol " + sym);
				}
				return new Iterable<UserPredicate>() {

					@Override
					public Iterator<UserPredicate> iterator() {
						return new FactIterator(sym, db.getAll(sym).iterator());
					}

				};
			}

			@Override
			public Iterable<UserPredicate> getQueryAnswer() {
				if (query == null) {
					return null;
				}
				RelationSymbol querySym = query.getSymbol();
				return new Iterable<UserPredicate>() {

					@Override
					public Iterator<UserPredicate> iterator() {
						return new FactIterator(querySym, db.getAll(querySym).iterator());
					}

				};
			}

			@Override
			public Set<RelationSymbol> getSymbols() {
				return Collections.unmodifiableSet(db.getSymbols());
			}

		};
	}

	static class FactIterator implements Iterator<UserPredicate> {

		final RelationSymbol sym;
		final Iterator<Term[]> bindings;

		public FactIterator(RelationSymbol sym, Iterator<Term[]> bindings) {
			this.sym = sym;
			this.bindings = bindings;
		}

		@Override
		public boolean hasNext() {
			return bindings.hasNext();
		}

		@Override
		public UserPredicate next() {
			return UserPredicate.make(sym, bindings.next(), false);
		}

	}

	@Override
	public boolean hasQuery() {
		return query != null;
	}

	@Override
	public UserPredicate getQuery() {
		return query;
	}

	public SortedIndexedFactDb getDb() {
		return db;
	}

	public SortedIndexedFactDb getDeltaDb() {
		return deltaDb;
	}

}