package org.wikidata.gremlin

import groovy.lang.Closure;

class RexsterInit
{
	static RexsterInit init(script)
	{
		return new RexsterInit().setup()
	}
	
	protected def setup()
	{
		getDSL().setup()
		this
	}

	protected DomainSpecificLanguage getDSL()
	{
		return new DomainSpecificLanguage()
	}

	int benchmark(Closure c)
	{
		def t = System.currentTimeMillis()
		c()
		def res = (System.currentTimeMillis() - t)
		println res
		res
	}

	def measure(int ntimes, Closure c)
	{
		def i = 0
		def res = []
		5.times {
			def t = System.currentTimeMillis()
			ntimes.times c
			res[i] = (System.currentTimeMillis() - t)
			i++
			println i
		}
		def avg = res.sum() / res.size()
		println res
		println "Average: $avg"
		println "Time: ${avg/ntimes} ms"
		[avg: avg, times: res, time: avg/ntimes]
	}
}

