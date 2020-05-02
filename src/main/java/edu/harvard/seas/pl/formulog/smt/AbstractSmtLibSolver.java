package edu.harvard.seas.pl.formulog.smt;

/*-
 * #%L
 * FormuLog
 * %%
 * Copyright (C) 2018 - 2020 President and Fellows of Harvard College
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import edu.harvard.seas.pl.formulog.Configuration;
import edu.harvard.seas.pl.formulog.ast.Constructors.SolverVariable;
import edu.harvard.seas.pl.formulog.ast.Program;
import edu.harvard.seas.pl.formulog.ast.SmtLibTerm;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.eval.EvaluationException;
import edu.harvard.seas.pl.formulog.smt.SmtLibShim.SmtStatus;
import edu.harvard.seas.pl.formulog.util.Pair;

public abstract class AbstractSmtLibSolver implements SmtLibSolver {

	private static final ExternalSolverProcessFactory solverFactory;
	static {
		switch (Configuration.smtSolver) {
		case "z3":
			solverFactory = Z3ProcessFactory.get();
			break;
		case "cvc4":
			solverFactory = Cvc4ProcessFactory.get();
			break;
		case "yices":
			solverFactory = YicesProcessFactory.get();
			break;
		default:
			throw new AssertionError("impossible");
		}
	}
	private static final AtomicInteger solverCnt = new AtomicInteger();

	protected final int solverId = solverCnt.getAndIncrement();

	protected SmtLibShim shim;
	protected Process solver;
	private final PrintWriter log;

	public AbstractSmtLibSolver() {
		PrintWriter w = null;
		if (Configuration.debugSmt) {
			try {
				Path path = Paths.get(Configuration.debugSmtOutDir);
				File dir = path.toFile();
				if (!dir.exists()) {
					dir.mkdirs();
				}
				File log = path.resolve("solver" + solverId + ".log.smt2").toFile();
				w = new PrintWriter(new FileWriter(log));
			} catch (IOException e) {
				System.err.println("WARNING: Unable to create log for solver #" + solverId);
			}
		}
		log = w;
	}

	public synchronized void start(Program<?, ?> prog) {
		assert solver == null;
		try {
			solver = solverFactory.newProcess();
		} catch (IOException e) {
			throw new AssertionError("Could not create external solver process:\n" + e);
		}
		BufferedReader reader = new BufferedReader(new InputStreamReader(solver.getInputStream()));
		PrintWriter writer = new PrintWriter(solver.getOutputStream());
		shim = new SmtLibShim(reader, writer, log);
		boolean declareAdts = Configuration.smtSolver.equals("z3") && Configuration.smtLogic.equals("ALL");
		shim.initialize(prog, declareAdts);
		start();
	}

	protected abstract void start();

	public synchronized void destroy() {
		assert solver != null;
		solver.destroy();
		solver = null;
	}

	@Override
	public void finalize() {
		destroy();
	}

	protected abstract Pair<List<SolverVariable>, List<SolverVariable>> makeAssertions(List<SmtLibTerm> assertions);

	protected abstract void cleanup();

	@Override
	public synchronized Pair<SmtStatus, Map<SolverVariable, Term>> check(List<SmtLibTerm> assertions, boolean getModel,
			int timeout) throws EvaluationException {
		assert solver != null;
		if (assertions.isEmpty()) {
			Map<SolverVariable, Term> m = getModel ? Collections.emptyMap() : null;
			return new Pair<>(SmtStatus.SATISFIABLE, m);
		}
		assertions = new ArrayList<>(new LinkedHashSet<>(assertions));
		boolean debug = log != null;
		Pair<List<SolverVariable>, List<SolverVariable>> p = makeAssertions(assertions);
		long start = 0;
		if (debug || Configuration.timeSmt) {
			start = System.currentTimeMillis();
		}
		try {
			SmtStatus status = shim.checkSatAssuming(p.fst(), p.snd(), timeout);
			if (Configuration.timeSmt || debug) {
				long time = System.currentTimeMillis() - start;
				Configuration.recordSmtEvalTime(solverId, time);
				if (debug) {
					log.println("; time: " + time + "ms");
					log.flush();
				}
			}
			Map<SolverVariable, Term> m = null;
			if (status.equals(SmtStatus.SATISFIABLE) && getModel) {
				m = shim.getModel();
			}
			cleanup();
			return new Pair<>(status, m);
		} catch (EvaluationException e) {
			throw new EvaluationException("Problem with solver " + solverId + ":\n" + e.getMessage());
		}
	}

}