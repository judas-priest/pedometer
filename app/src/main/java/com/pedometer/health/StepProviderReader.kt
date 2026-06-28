package com.pedometer.health

import android.content.Context
import android.net.Uri
import android.util.Log

object StepProviderReader {
    private const val TAG = "StepProviderReader"
    private val AUTHORITY = "com.oplus.healthservice.stepprovider"

    fun readSteps(context: Context) {
        try {
            val uri = Uri.parse("content://$AUTHORITY")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor == null) {
                Log.w(TAG, "Cursor is null")
                return
            }
            Log.i(TAG, "Got cursor with ${cursor.count} rows, ${cursor.columnCount} columns")
            Log.i(TAG, "Columns: ${cursor.columnNames.joinToString()}")
            while (cursor.moveToNext()) {
                val row = (0 until cursor.columnCount).joinToString(" | ") { i ->
                    "${cursor.getColumnName(i)}=${cursor.getString(i)}"
                }
                Log.i(TAG, "Row: $row")
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read StepProvider", e)
        }
    }

    fun tryPaths(context: Context) {
        val paths = listOf("", "/steps", "/step", "/today", "/daily", "/count")
        for (path in paths) {
            try {
                val uri = Uri.parse("content://$AUTHORITY$path")
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    Log.i(TAG, "Path '$path': ${cursor.count} rows, columns=${cursor.columnNames.joinToString()}")
                    if (cursor.moveToFirst()) {
                        val row = (0 until cursor.columnCount).joinToString(" | ") { i ->
                            "${cursor.getColumnName(i)}=${cursor.getString(i)}"
                        }
                        Log.i(TAG, "  First row: $row")
                    }
                    cursor.close()
                } else {
                    Log.w(TAG, "Path '$path': null cursor")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Path '$path': ${e.message}")
            }
        }
    }
}
