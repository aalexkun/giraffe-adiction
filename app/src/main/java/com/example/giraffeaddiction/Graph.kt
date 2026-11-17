package com.example.giraffeaddiction

import android.content.Context
import com.example.giraffeaddiction.data.AppDatabase
import com.example.giraffeaddiction.data.GiraffeRepository

/**
 * The app's object graph, by hand. Two singletons do not justify a DI framework, and the service
 * and the UI both need the same database instance.
 */
object Graph {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: GiraffeRepository
        private set

    @Synchronized
    fun init(context: Context) {
        if (::db.isInitialized) return
        db = AppDatabase.build(context)
        repo = GiraffeRepository(db)
    }
}
