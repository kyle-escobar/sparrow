package io.rsbox.sparrow.deobfuscator.transform

import com.google.common.collect.Multimap
import com.google.common.collect.MultimapBuilder
import io.rsbox.sparrow.deobfuscator.Transformer
import io.rsbox.sparrow.deobfuscator.asm.ClassGroup
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.tinylog.kotlin.Logger
import java.util.*

/**
 * Copyright (c) 2020 RSBox
 *
 * Licensed under GNU General Public License v3.0
 * Please read the LICENSE file for more details.
 *
 * @author Kyle Escobar
 */

/**
 * Removes methods which have not call graph entries.
 */
class UnusedMethodRemover : Transformer {

    /**
     * Removes the unused methods.
     *
     * @param group ClassGroup
     */
    override fun transform(group: ClassGroup) {
        val unusedMethods = group.unusedMethods
        var counter = 0

        group.forEach { c ->
            val methodIterator = c.methods.iterator()
            while(methodIterator.hasNext()) {
                val m = methodIterator.next()
                val mName = c.name + "." + m.name + m.desc

                if(mName !in unusedMethods) continue
                methodIterator.remove()
                counter++
            }
        }

        Logger.info("Removed $counter unused methods.")
    }

    /**
     * A [TreeSet] of the unused methods in a [ClassGroup]
     */
    private val ClassGroup.unusedMethods: TreeSet<String> get() {
        val namedGroup = this.associateBy { it.name }

        val supers = MultimapBuilder.hashKeys().arrayListValues().build<ClassNode, String>()
        this.forEach { c ->
            c.interfaces.forEach { i ->
                supers.put(c, i)
            }
            supers.put(c, c.superName)
        }

        val subs = MultimapBuilder.hashKeys().arrayListValues().build<ClassNode, String>()
        supers.forEach { k, v ->
            if(namedGroup.containsKey(v)) {
                subs.put(namedGroup.getValue(v), k.name)
            }
        }

        val usedMethods = this.asSequence().flatMap { it.methods.asSequence() }
            .flatMap { it.instructions.iterator().asSequence() }
            .mapNotNull { it as? MethodInsnNode }
            .map { it.owner + "." + it.name + it.desc }
            .toSet()

        val removedMethods = TreeSet<String>()
        this.forEach { c ->
            c.methods.forEach methodLoop@ { m ->
                if(isMethodUsed(c, m, usedMethods, supers, subs, namedGroup)) return@methodLoop
                val mName = c.name + "." + m.name + m.desc
                removedMethods.add(mName)
            }
        }

        return removedMethods
    }

    /**
     * Check whether a method is invoked anywhere in a class group.
     *
     * @param node ClassNode
     * @param method MethodNode
     * @param usedMethods Set<String>
     * @param supers Multimap<ClassNode, String>
     * @param subs Multimap<ClassNode, String>
     * @param namedGroup Map<String, ClassNode>
     * @return Boolean
     */
    private fun isMethodUsed(
        node: ClassNode,
        method: MethodNode,
        usedMethods: Set<String>,
        supers: Multimap<ClassNode, String>,
        subs: Multimap<ClassNode, String>,
        namedGroup: Map<String, ClassNode>
    ): Boolean {
        if(method.name == "<init>" || method.name == "<clinit>") return true
        val mName = node.name + "." + method.name + method.desc
        if(usedMethods.contains(mName)) return true
        var currSupers: Collection<String> = supers[node]
        while (currSupers.isNotEmpty()) {
            currSupers.forEach { c ->
                if (isJdkMethod(c, method.name, method.desc)) return true
                if (usedMethods.contains(c + "." + method.name + method.desc)) return true
            }
            currSupers = currSupers.filter { namedGroup.containsKey(it) }.flatMap { supers[namedGroup.getValue(it)] }
        }
        var currSubs = subs[node]
        while (currSubs.isNotEmpty()) {
            currSubs.forEach { c ->
                if (usedMethods.contains(c + "." + method.name + method.desc)) return true
            }
            currSubs = currSubs.flatMap { subs[namedGroup.getValue(it)] }
        }
        return false
    }

    /**
     * Checks if the method is a OSRS gamepack method or is apart of the JVM std library.
     *
     * @param internalClassName String
     * @param methodName String
     * @param methodDesc String
     * @return Boolean
     */
    private fun isJdkMethod(internalClassName: String, methodName: String, methodDesc: String): Boolean {
        try {
            var classes = listOf(Class.forName(Type.getObjectType(internalClassName).className))
            while (classes.isNotEmpty()) {
                for (c in classes) {
                    if (c.declaredMethods.any { it.name == methodName && Type.getMethodDescriptor(it) == methodDesc }) return true
                }
                classes = classes.flatMap {
                    ArrayList<Class<*>>().apply {
                        addAll(it.interfaces)
                        if (it.superclass != null) add(it.superclass)
                    }
                }
            }
        } catch (e: Exception) {

        }
        return false
    }
}