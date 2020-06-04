package io.rsbox.sparrow.deobfuscator.transform

import com.google.common.collect.TreeMultimap
import io.rsbox.sparrow.deobfuscator.Transformer
import io.rsbox.sparrow.deobfuscator.asm.ClassGroup
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Modifier

/**
 * Copyright (c) 2020 RSBox
 *
 * Licensed under GNU General Public License v3.0
 * Please read the LICENSE file for more details.
 *
 * @author Kyle Escobar
 */

class DuplicateMethodRemover : Transformer {

    override fun transform(group: ClassGroup) {
        val map = TreeMultimap.create<String, String>()
        group.forEach { c ->
            c.methods.filter { Modifier.isStatic(it.access) && it.name != "<clinit>" }.forEach { m ->
                map.put(m.id(), c.name + "." + m.name + m.desc)
            }
        }

        map.asMap().entries.removeIf { it.value.size == 1 }
    }

    private fun MethodNode.id(): String {
        return "${ Type.getReturnType(desc)}." + (instructions.lineNumberRange() ?: "*") + "." + instructions.hash()
    }

    private fun InsnList.lineNumberRange(): IntRange? {
        val lns = iterator().asSequence().mapNotNull { it as? LineNumberNode }.mapNotNull { it.line }.toList()
        if(lns.isEmpty()) return null
        return lns.first()..lns.last()
    }

    private fun InsnList.hash(): Int {
        return iterator().asSequence().mapNotNull {
            when(it) {
                is FieldInsnNode -> it.owner + "." + it.name + ":" + it.opcode
                is MethodInsnNode -> it.opcode.toString() + ":" + it.owner + "." + it.name
                is InsnNode -> it.opcode.toString()
                else -> null
            }
        }.toSet().hashCode()
    }
}