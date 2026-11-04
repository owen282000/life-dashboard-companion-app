package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DataExportCsvTest {

    @Test
    fun aRowPerRecordWithAColumnForEveryField() {
        val csv = DataExportCsv.fromPayload(
            """{"timestamp":"t","app_version":"1","steps":[{"uuid":"a","start_time":"s","count":12}],"weight":[{"uuid":"b","time":"w","kilograms":70.5}]}"""
        )
        assertEquals(
            "data_type,uuid,time,start_time,count,kilograms\n" +
                "steps,a,,s,12,\n" +
                "weight,b,w,,,70.5\n",
            csv
        )
    }

    @Test
    fun aNestedValueIsCompactJsonInItsCell() {
        val csv = DataExportCsv.fromPayload("""{"screen_time":[{"date":"d","apps":[{"package":"p","minutes":3}]}]}""")
        assertEquals(
            "data_type,date,apps\n" +
                "screen_time,d,\"[{\"\"package\"\":\"\"p\"\",\"\"minutes\"\":3}]\"\n",
            csv
        )
    }

    @Test
    fun textWithACommaOrLineBreakIsQuoted() {
        val csv = DataExportCsv.fromPayload("""{"notes":[{"text":"a,b\nc","empty":null}]}""")
        assertEquals("data_type,empty,text\nnotes,,\"a,b\nc\"\n", csv)
    }

    @Test
    fun whatIsNoListOfRecordsStaysOut() {
        assertEquals(
            "data_type\n",
            DataExportCsv.fromPayload("""{"timestamp":"t","deleted_records":["a","b"],"_resolutions":{"heart_rate":"5m"}}""")
        )
    }
}
