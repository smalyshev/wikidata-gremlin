package org.wikidata.gremlin
import groovy.json.JsonLexer
import java.io.Reader
import groovy.json.JsonTokenType

class JSONReader {
	final JsonLexer lexer
	private boolean started
	private def container
	private def stack
	private def key
	
	JSONReader(Reader input) {
		this.lexer = new JsonLexer(input)
		this.started = false
		this.container = null
		this.stack = []
	}
	
	private def addToContainer(item)
	{
		if(key == false) {
			container.add(item)
		} else {
			container[key] = item
		}
		return item
	}
	
	public def getNextItem()
	{
		def t = lexer.nextToken()
		if(!t) {
			return
		}
		try {
			if(!started) {
				// Startup - skip first [
				assert t.type == JsonTokenType.OPEN_BRACKET
				started = true
				t = lexer.nextToken()
			}

			if(t.type == JsonTokenType.CLOSE_BRACKET) {
				// we're done, yay!
				return null
			}
			
			if(t.type == JsonTokenType.COMMA) {
				// ok, next one
				t = lexer.nextToken()
			}
		
			// only accept objects as items for now
			assert t.type == JsonTokenType.OPEN_CURLY
			container = [:]
			key = true
		
			while(t = lexer.nextToken()) {
				switch(t.type) {
					case JsonTokenType.OPEN_BRACKET:
						def n = addToContainer([])
						stack.add([this.container, this.key])
						container = n
						key = false
						break;
					case JsonTokenType.CLOSE_BRACKET:
						(container, key) = this.stack.pop()
						break;
					case JsonTokenType.OPEN_CURLY:
						def n = addToContainer([:])
						stack.add([container, key])
						container = n
						key = true
						break;
					case JsonTokenType.CLOSE_CURLY:
						if(stack.size() == 0) {
							// done with this object
							return container
						}
						(container, key) = stack.pop()
						break;
					case JsonTokenType.COMMA:
						if(key != false) {
							// reset - expecting key again
							key = true
						} 
						break;
					default:
						if(key == false) {
							addToContainer(t.value)
						} else if(key == true) {
							// expecting key
							assert t.type == JsonTokenType.STRING
							key = t.value 
							// consume colon
							t = lexer.nextToken()
							assert t.type == JsonTokenType.COLON
						} else {
							// add value to container
							addToContainer(t.value)
						}
				}
			}
		} catch(e) {
			if(t) {
				println "Failed on token: $t line ${t.getStartLine()} col ${t.getStartColumn()} text ${t.getText()}"
			} else {
				println "Failed to read token: $e"
			}
			throw e
		}
	}
}