package dk.cachet.rad.study

import dk.cachet.carp.common.DateTime
import java.util.*

class DateServiceImpl : DateService {
    override suspend fun getDate(): DateTime {
        return DateTime.now()
    }

    override suspend fun getDateAsString(prefix: String): String {
        return "$prefix ${DateTime.now()}"
    }

    override suspend fun getDateAsDate(): Date {
        return Date()
    }


    // Add parameter to getDate
    // add getDateAsString
    // modify getDate to return a date as a string

    //override suspend fun getDateAsString(): String {
    //    return Date().toString()
    //}

    //override suspend fun getOffsetDate(offset: Int): Calendar {
    //    val date = GregorianCalendar()
    //    date.add(Calendar.DATE, offset)
    //    return date
    //}
}