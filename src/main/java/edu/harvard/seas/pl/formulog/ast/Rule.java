package edu.harvard.seas.pl.formulog.ast;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

public interface Rule<H extends Literal, B extends Literal> extends Iterable<B> {

	H getHead();

	int getBodySize();

	B getBody(int idx);

	default Map<Var, Integer> countVariables() {
		Map<Var, Integer> m = new HashMap<>();
		for (Term arg : getHead().getArgs()) {
			arg.updateVarCounts(m);
		}
		for (Literal l : this) {
			for (Term arg : l.getArgs()) {
				arg.updateVarCounts(m);
			}
		}
		return Collections.unmodifiableMap(m);
	}

}
