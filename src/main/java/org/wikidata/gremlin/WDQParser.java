package org.wikidata.gremlin;

import org.parboiled.BaseParser;
import org.parboiled.Rule;

public class WDQParser extends BaseParser<Object> {

	public Rule WDQ() {
		return Expression();
	}

	public Rule Expression()
	{
		return Claim();
	}

	public Rule ClaimType()
	{
		return FirstOf("claim", "noclaim");
	}

	public Rule Claim()
	{
		return Sequence(ClaimType(), "[",
					Number(),
					Optional(ZeroOrMore(Sequence(",",Item()))),
					"]"
					);
	}

	public Rule Item()
	{
		return FirstOf(Number(), Sequence("(", Expression(), ")"));
	}

	Rule Number() {
        return OneOrMore(Digit());
    }

    Rule Digit() {
        return CharRange('0', '9');
    }

}
