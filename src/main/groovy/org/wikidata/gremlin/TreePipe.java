package org.wikidata.gremlin;

import java.util.NoSuchElementException;

import org.apache.commons.lang.StringUtils;

import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.gremlin.groovy.Gremlin;
import com.tinkerpop.pipes.AbstractPipe;
import com.tinkerpop.pipes.Pipe;
import com.tinkerpop.pipes.transform.TransformPipe;
import com.tinkerpop.pipes.util.iterators.SingleIterator;

public class TreePipe extends AbstractPipe<Vertex,Vertex> implements TransformPipe<Vertex, Vertex> {
	public enum Direction {
		IN, OUT
	}

	private Pipe<Vertex, Vertex> subtree = null;
	private String[] props;
	private Direction dir;

	public TreePipe(Direction dir, String... props) {
		if(props.length == 0) {
			throw new RuntimeException("Property list should not be empty");
		}
		this.props = props;
		this.dir = dir;
	}

	@SuppressWarnings("unchecked")
	public Vertex processNextStart() throws NoSuchElementException {
		if(subtree == null) {
			Vertex v = this.starts.next();
			String propsList = StringUtils.join(props, "','");
			String dirCall = ((dir == Direction.IN)?"in":"out")+"('"+propsList+"')";
			String groovy = String.format("_().as('x').%s.loop('x'){it.object.%s.hasNext()}{true}.dedup()",
					dirCall, dirCall);
			System.out.println(groovy);
			subtree = Gremlin.compile(groovy);
			subtree.setStarts(new SingleIterator<Vertex>(v));
			return v;
		} else {
			return subtree.next();
		}
	}
}
