package edu.harvard.seas.pl.formulog.smt;

import java.util.List;

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

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import edu.harvard.seas.pl.formulog.ast.BasicRule;
import edu.harvard.seas.pl.formulog.ast.Constructors.SolverVariable;
import edu.harvard.seas.pl.formulog.ast.Program;
import edu.harvard.seas.pl.formulog.ast.SmtLibTerm;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.UserPredicate;
import edu.harvard.seas.pl.formulog.eval.EvaluationException;
import edu.harvard.seas.pl.formulog.smt.SmtLibShim.SmtStatus;
import edu.harvard.seas.pl.formulog.util.Pair;

public class QueueSmtManager extends AbstractSmtManager {

	private final ArrayBlockingQueue<SmtLibSolver> solvers;

	public QueueSmtManager(Program<UserPredicate, BasicRule> prog, int size) {
		solvers = new ArrayBlockingQueue<>(size);
		for (int i = 0; i < size; ++i) {
			CheckSatAssumingSolver solver = new CheckSatAssumingSolver();
			solver.start(prog);
			solvers.add(solver);
		}
	}

	@Override
	public Pair<SmtStatus, Map<SolverVariable, Term>> check(List<SmtLibTerm> conjuncts, boolean getModel, int timeout)
			throws EvaluationException {
		SmtLibSolver solver;
		try {
			solver = solvers.take();
		} catch (InterruptedException e) {
			throw new EvaluationException(e);
		}
		Pair<SmtStatus, Map<SolverVariable, Term>> res = solver.check(conjuncts, getModel, timeout);
		solvers.add(solver);
		return res;
	}

}
