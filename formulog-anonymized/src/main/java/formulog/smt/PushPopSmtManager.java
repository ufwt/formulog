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



import java.util.Collection;

import formulog.ast.Program;
import formulog.ast.SmtLibTerm;
import formulog.eval.EvaluationException;

public class PushPopSmtManager implements SmtManager {

	private final PushPopSolver solver = new PushPopSolver();

	@Override
	public SmtResult check(Collection<SmtLibTerm> assertions, boolean getModel, int timeout)
			throws EvaluationException {
		return solver.check(assertions, getModel, timeout);
	}

	@Override
	public void initialize(Program<?, ?> prog) throws EvaluationException {
		solver.start(prog);
	}

}