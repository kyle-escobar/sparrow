package io.rsbox.sparrow.deobfuscator.transform

import io.rsbox.sparrow.deobfuscator.Transformer
import io.rsbox.sparrow.deobfuscator.asm.ClassNodeGroup
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.PUTSTATIC
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.tinylog.kotlin.Logger
import java.lang.reflect.Modifier

/**
 * Copyright (c) 2020 RSBox
 *
 * Licensed under GNU General Public License v3.0
 * Please read the LICENSE file for more details.
 *
 * @author Kyle Escobar
 */

/**
 * Moves static fields into classes which is the only place
 * it is invoked.
 */
class FieldInliner : Transformer {

    override fun transform(group: ClassNodeGroup) {
        var counter = 0

        val resolver = FieldResolver(group)

        group.forEach { c ->
            c.methods.forEach { m ->
                m.instructions.iterator().forEach { insn ->
                    if(insn is FieldInsnNode) {
                        val opcode = insn.opcode
                        val oldOwner = insn.owner
                        insn.owner = resolver.getOwner(
                            insn.owner,
                            insn.name,
                            insn.desc,
                            (opcode == GETSTATIC || opcode == PUTSTATIC)
                        )

                        val newOwner = insn.owner
                        if(oldOwner != newOwner) counter++
                    }
                }
            }
        }

        Logger.info("Inlined $counter static fields.")
    }

    /**
     * Represents a field call graph resolver object.
     *
     * @property group ClassGroup
     * @constructor
     */
    private class FieldResolver(private val group: ClassNodeGroup) {

        /**
         * A map of [group] to the class name as a key.
         */
        private val namedGroup = group.associateBy { it.name }

        /**
         * Gets the proper owner of a field by analyzing the invoke tree
         * of a given field.
         *
         * @param owner String
         * @param name String
         * @param desc String
         * @param isStatic Boolean
         * @return String
         */
        fun getOwner(owner: String, name: String, desc: String, isStatic: Boolean): String {

            var node = namedGroup[owner] ?: return owner

            /**
             * Loop forever until the block returns a value.
             */
            while(true) {
                if(node.hasDeclaredField(name, desc, isStatic)) {
                    return node.name
                }

                val superName = node.superName
                node = namedGroup[superName] ?: return superName
            }
        }


        /**
         * Checks if a [ClassNode] has a field matching the inputs.
         *
         * @receiver ClassNode
         * @param name String
         * @param desc String
         * @param isStatic Boolean
         * @return Boolean
         */
        private fun ClassNode.hasDeclaredField(name: String, desc: String, isStatic: Boolean): Boolean {
            return this.fields.any {
                it.name == name && it.desc == desc && Modifier.isStatic(it.access) == isStatic
            }
        }
    }
}