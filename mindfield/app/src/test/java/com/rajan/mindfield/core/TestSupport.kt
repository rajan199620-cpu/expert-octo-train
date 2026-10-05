package com.rajan.mindfield.core

import java.io.File

/** The real library from src/main/assets, shared by the pure-Kotlin tests. */
object TestLibrary {
    val file: File by lazy {
        val candidates = listOfNotNull(
            System.getProperty("mindfield.assets")?.let { File(it, "concepts.txt") },
            File("src/main/assets/concepts.txt"),
            File("app/src/main/assets/concepts.txt"),
        )
        candidates.firstOrNull { it.exists() } ?: error("concepts.txt not found in $candidates")
    }
    val text: String by lazy { file.readText() }
    val library: Library by lazy { Library.parse(text) }
}
