package org.wikidata.gremlin
import org.joda.time.*;
import org.joda.time.format.*;
class DateUtils {
	// Rough number of seconds in a year, for storing whole years
	// Source: https://en.wikipedia.org/wiki/Year#Astronomical_years
	public final static long SECONDS_IN_YEAR = 31557600L;
	// Largest year that Java can represent accurately.
	// This is just an threshold, beyond this year we do not try to parse dates
	// but store (number of whole years)*SECONDS_IN_YEAR.
	public final static long LARGEST_YEAR = 292000000L;
	// Parser for year as dates
	private final static def yearParser = DateTimeFormat.forPattern('y').withZone(DateTimeZone.UTC)
	// Seconds for largest Java-representable date
	public final static long LARGEST_DATE_SECONDS = yearParser.parseDateTime(LARGEST_YEAR as String).getMillis()/1000;
	// Seconds for lower bound of dates we represent as Java dates
	public final static long LOWEST_DATE_SECONDS = yearParser.parseDateTime("0").getMillis()/1000;

	public final static DateTime dateMax = new DateTime(LARGEST_DATE_SECONDS*1000+1, DateTimeZone.UTC)
	public final static DateTime dateMin = new DateTime(LOWEST_DATE_SECONDS*1000-1, DateTimeZone.UTC)

	/**
	 * Can this date be converted to Java date?
	 * @param seconds
	 * @return
	 */
	public static boolean javaDate(long seconds)
	{
		return seconds < LARGEST_DATE_SECONDS && seconds > LOWEST_DATE_SECONDS;
	}

	/**
	 * Create Java DateTime object from seconds
	 * @param seconds
	 * @return DateTime
	 */
	public static DateTime toJavaDate(long seconds) {
		if(!javaDate(seconds)) {
			/* TODO: not sure what to return here, maybe exception? */
			return seconds>0?dateMax:dateMin;
		}
		return new DateTime(seconds*1000, DateTimeZone.UTC)
	}

	/**
	 * Get seconds from year (assuming it is YYYY-01-01 00:00:00)
	 * @param year
	 * @return
	 */
	public static long fromYear(long year) {
		if(year >= LARGEST_YEAR) {
			return SECONDS_IN_YEAR*(year-LARGEST_YEAR)+LARGEST_DATE_SECONDS;
		}
		if(year <= 0) {
			return SECONDS_IN_YEAR*year+LOWEST_DATE_SECONDS;
		}
		yearParser.parseDateTime(year as String).getMillis()/1000
	}

	/**
	 * Translate from seconds to year
	 * Only to be used for non-Java date years
	 * @param seconds
	 * @return
	 */
	public static long asYear(long seconds) {
		if(seconds > 0) {
			return (seconds-LARGEST_DATE_SECONDS)/SECONDS_IN_YEAR+LARGEST_YEAR;
		}
		return (seconds-LOWEST_DATE_SECONDS)/SECONDS_IN_YEAR;
	}

	private final static def df = DateTimeFormat.forPattern('y-M-d')
	/**
	 * Get seconds from date YYYY-MM-DD
	 * This makes sense only within gregorian calendar times
	 * @param isoDate
	 * @return
	 */
	public static long fromDate(String isoDate) {
		return fromDate(df.parseDateTime(isoDate))
	}

	/**
	 * Get seconds from Java date
	 * @param isoDate
	 * @return
	 */
	public static long fromDate(Date javaDate) {
		return javaDate.getTime()/1000;
	}

	/**
	 * Get seconds from Java date
	 * @param isoDate
	 * @return
	 */
	public static long fromDate(DateTime javaDate) {
		return javaDate.getMillis()/1000;
	}

	private final static def printForm = ISODateTimeFormat.dateTimeNoMillis().withZone(DateTimeZone.UTC)
	/**
	 * Returns ISO8601 (extended) representation of the date
	 * @return
	 */
	public static String asString(long seconds) {
		if(!javaDate(seconds)) {
			return sprintf("%04d-01-01T00:00:00Z", asYear(seconds));
		}
		return printForm.print(new DateTime(seconds*1000, DateTimeZone.UTC))
	}
}
