package formulog.smt;

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



import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import formulog.Configuration;
import formulog.ast.SmtLibTerm;
import formulog.ast.Constructors.SolverVariable;
import formulog.eval.EvaluationException;
import formulog.util.Pair;

public class PushPopSolver extends AbstractSmtLibSolver {

	private final Deque<SmtLibTerm> cache = new ArrayDeque<>();

	private final static Pair<List<SolverVariable>, List<SolverVariable>> emptyListPair = new Pair<>(
			Collections.emptyList(), Collections.emptyList());

	@Override
	protected Pair<List<SolverVariable>, List<SolverVariable>> makeAssertions(Collection<SmtLibTerm> assertions)
			throws EvaluationException {
		int baseSize = cache.size();
		int i = findDiffPos(assertions);
		int pops = baseSize - i;
		shrinkCache(i);
		Iterator<SmtLibTerm> it = assertions.iterator();
		for (int j = 0; j < i; ++j) {
			it.next();
		}
		growCache(it);
		if (Configuration.timeSmt) {
			Configuration.recordPushPopSolverStats(solverId, baseSize, pops, cache.size() - i);
		}
		return emptyListPair;
	}

	private int findDiffPos(Collection<SmtLibTerm> assertions) {
		int i = 0;
		Iterator<SmtLibTerm> cacheIt = cache.iterator();
		for (SmtLibTerm assertion : assertions) {
			if (!cacheIt.hasNext()) {
				break;
			}
			SmtLibTerm cached = cacheIt.next();
			if (!cached.equals(assertion)) {
				break;
			}
			++i;
		}
		return i;
	}

	private void shrinkCache(int tgtSize) throws EvaluationException {
		int size = cache.size();
		while (size > tgtSize) {
			shim.pop();
			cache.removeLast();
			--size;
		}
	}

	private void growCache(Iterator<SmtLibTerm> assertions) throws EvaluationException {
		while (assertions.hasNext()) {
			SmtLibTerm assertion = assertions.next();
			shim.push();
			shim.makeAssertion(assertion);
			cache.addLast(assertion);
		}
	}

	@Override
	protected void cleanup() {
		// Do nothing
	}

	@Override
	protected void start() throws EvaluationException {
		shim.setLogic(Configuration.smtLogic);
		shim.makeDeclarations();
	}

}