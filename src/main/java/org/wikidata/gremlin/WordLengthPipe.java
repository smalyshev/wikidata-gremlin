package org.wikidata.gremlin;

import com.tinkerpop.pipes.AbstractPipe;
import com.tinkerpop.pipes.transform.TransformPipe;

public class WordLengthPipe extends AbstractPipe<String, Integer> implements TransformPipe<String,Integer> {
	public Integer processNextStart() {
	  String start = this.starts.next();
	  return start.length();
	}
}

