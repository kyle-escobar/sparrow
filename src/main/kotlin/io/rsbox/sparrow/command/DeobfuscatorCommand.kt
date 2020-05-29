package io.rsbox.sparrow.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import org.tinylog.kotlin.Logger

/**
 * Copyright (c) 2020 RSBox
 *
 * Licensed under GNU General Public License v3.0
 * Please read the LICENSE file for more details.
 *
 * @author Kyle Escobar
 */

/**
 * The sparrow deobfuscator command.
 */
class DeobfuscatorCommand : CliktCommand(
    name = "deobfuscate",
    help = "Deobfuscates a raw OSRS gamepack to a form which can be decompiled.",
    printHelpOnEmptyArgs = true
) {

    /**
     * The command context configuration
     */
    private val config by requireObject<MutableMap<String, Any>>()

    /**
     * The input source JAR file.
     */
    private val sourceJar by option("-s", "--source", help = "The source / input JAR file path.")
        .file(mustExist = true, canBeDir = false).required()

    /**
     * The output JAR file
     */
    private val outputJar by option("-o", "--output", help = "The output JAR file path.")
        .file(mustExist = false, canBeDir = false).required()

    override fun run() {
        config["sourceJar"] = sourceJar
        config["outputJar"] = outputJar

        // temp
        Logger.info("Invoking deobfuscator.")
    }
}