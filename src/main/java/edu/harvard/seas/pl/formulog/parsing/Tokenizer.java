package edu.harvard.seas.pl.formulog.parsing;

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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;

public class Tokenizer {

	private final Reader reader;
	private int bufSize = 1024;
	private final char[] buf = new char[bufSize];
	private int pos = buf.length;

	private final Deque<TokenItem> queue = new ArrayDeque<TokenItem>();

	public Tokenizer(Reader reader) {
		this.reader = reader;
	}

	public boolean hasNext() throws IOException {
		return load();
	}

	public TokenItem next() throws IOException {
		load();
		try {
			return queue.remove();
		} catch (NoSuchElementException e) {
			throw new NoSuchElementException("Token stream is exhausted");
		}
	}

	public TokenItem peek() throws IOException {
		load();
		try {
			return queue.getFirst();
		} catch (NoSuchElementException e) {
			throw new NoSuchElementException("Token stream is exhausted");
		}
	}

	private boolean load() throws IOException {
		if (!queue.isEmpty()) {
			return true;
		}
		TokenItem next = loadToken();
		if (next == null) {
			return false;
		}
		queue.addFirst(next);
		return true;
	}

	private TokenItem loadToken() throws IOException {
		while (loadBuffer()) {
			char ch = buf[pos++];
			if (!Character.isWhitespace(ch)) {
				if (ch == '"') {
					return loadString();
				} else if (Character.isLetter(ch)) {
					pos--;
					return loadAlphabetic();
				} else if (Character.isDigit(ch)) {
					pos--;
					return loadNumber();
				} else if (ch == '.') {
					return TokenItems.period;
				} else if (ch == ':') {
					return loadInitialColon();
				} else if (ch == ',') {
					return TokenItems.comma;
				} else if (ch == ';') {
					return TokenItems.semicolon;
				}
				break;
			}
		}
		return null;
	}

	private boolean loadBuffer() throws IOException {
		if (pos == bufSize) {
			int size = reader.read(buf);
			if (size < 0) {
				size = 0;
			}
			pos = 0;
			bufSize = size;
			return bufSize != 0;
		}
		return true;
	}

	private TokenItem loadAlphabetic() throws IOException {
		String s = loadAlphaNumeric();
		switch (s) {
		case "fun":
			return TokenItems.fun;
		case "input":
			return TokenItems.input;
		case "output":
			return TokenItems.output;
		default:
			return new TokenItem(Token.ID, s);
		}
	}

	private TokenItem loadInitialColon() throws IOException {
		if (loadBuffer() && buf[pos] == '-') {
			pos++;
			return TokenItems.turnstile;
		}
		return TokenItems.colon;
	}

	private TokenItem loadNumber() {
		throw new UnsupportedOperationException();
	}

	private TokenItem loadString() {
		throw new UnsupportedOperationException();
	}
	
	private String loadAlphaNumeric() throws IOException {
		StringBuilder sb = new StringBuilder();
		char ch;
		while (loadBuffer() && Character.isLetterOrDigit((ch = buf[pos++]))) {
			sb.append(ch);
		}
		return sb.toString();
	}
	
	public static void main(String[] args) throws IOException {
		StringReader reader = new StringReader("fun input output foo : :- . ;");
		Tokenizer t = new Tokenizer(reader);
		while (t.hasNext()) {
			System.out.println(t.next());
		}
	}

}
