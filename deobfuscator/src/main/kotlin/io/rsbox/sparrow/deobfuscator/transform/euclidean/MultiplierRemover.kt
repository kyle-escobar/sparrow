package io.rsbox.sparrow.deobfuscator.transform.euclidean

import io.rsbox.sparrow.deobfuscator.Transformer
import io.rsbox.sparrow.deobfuscator.asm.ClassGroup
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.Type.LONG_TYPE
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.*
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
 * Responsible for removing primitive field multipliers.
 */
class MultiplierRemover : Transformer {

    override val priority = 10

    override fun transform(group: ClassGroup) {
        val multipliers = MultiplierFinder().getMultipliers(group)

        Logger.info("Found ${multipliers.size} primitive field multipliers.")

        group.forEach { c ->
            c.methods.forEach { m ->
                m.maxStack += 2
                cancelOutMultipliers(m, decoders = multipliers)
                solveConstantMath(c, m)
                m.maxStack -= 2
            }
        }

        Logger.info("Completed removal of ${multipliers.size} multipliers.")
    }


    private fun cancelOutMultipliers(m: MethodNode, decoders: Map<String, Long>) {
        val insnList = m.instructions
        for (insn in insnList.iterator()) {
            if (insn !is FieldInsnNode) continue
            if (insn.desc != INT_TYPE.descriptor && insn.desc != LONG_TYPE.descriptor) continue
            val fieldName = "${insn.owner}.${insn.name}"
            val decoder = decoders[fieldName] ?: continue
            when (insn.opcode) {
                GETFIELD, GETSTATIC -> {
                    when (insn.desc) {
                        INT_TYPE.descriptor -> {
                            when (insn.next.opcode) {
                                I2L -> insnList.insertSafe(insn.next, LdcInsnNode(invert(decoder)), InsnNode(LMUL))
                                else -> insnList.insertSafe(insn, LdcInsnNode(invert(decoder.toInt())), InsnNode(IMUL))
                            }
                        }
                        LONG_TYPE.descriptor -> insnList.insertSafe(insn, LdcInsnNode(invert(decoder)), InsnNode(LMUL))
                        else -> error(insn)
                    }
                }
                PUTFIELD -> {
                    when (insn.desc) {
                        INT_TYPE.descriptor -> {
                            when (insn.previous.opcode) {
                                DUP_X1 -> {
                                    insnList.insertBeforeSafe(insn.previous, LdcInsnNode(decoder.toInt()), InsnNode(IMUL))
                                    insnList.insertSafe(insn, LdcInsnNode(invert(decoder.toInt())), InsnNode(IMUL))
                                }
                                DUP, DUP_X2, DUP2, DUP2_X1, DUP2_X2 -> error(insn)
                                else -> insnList.insertBeforeSafe(insn, LdcInsnNode(decoder.toInt()), InsnNode(IMUL))
                            }
                        }
                        LONG_TYPE.descriptor -> {
                            when (insn.previous.opcode) {
                                DUP2_X1 -> {
                                    insnList.insertBeforeSafe(insn.previous, LdcInsnNode(decoder), InsnNode(LMUL))
                                    insnList.insertSafe(insn, LdcInsnNode(invert(decoder)), InsnNode(LMUL))
                                }
                                DUP, DUP_X1, DUP_X2, DUP2, DUP2_X2 -> error(insn)
                                else -> insnList.insertBeforeSafe(insn, LdcInsnNode(decoder), InsnNode(LMUL))
                            }
                        }
                        else -> error(insn)
                    }
                }
                PUTSTATIC -> {
                    when (insn.desc) {
                        INT_TYPE.descriptor -> {
                            when (insn.previous.opcode) {
                                DUP -> {
                                    insnList.insertBeforeSafe(insn.previous, LdcInsnNode(decoder.toInt()), InsnNode(IMUL))
                                    insnList.insertSafe(insn, LdcInsnNode(invert(decoder.toInt())), InsnNode(IMUL))
                                }
                                DUP_X1, DUP_X2, DUP2, DUP2_X1, DUP2_X2 -> error(insn)
                                else -> insnList.insertBeforeSafe(insn, LdcInsnNode(decoder.toInt()), InsnNode(IMUL))
                            }
                        }
                        LONG_TYPE.descriptor -> {
                            when (insn.previous.opcode) {
                                DUP2 -> {
                                    insnList.insertBeforeSafe(insn.previous, LdcInsnNode(decoder), InsnNode(LMUL))
                                    insnList.insertSafe(insn, LdcInsnNode(invert(decoder)), InsnNode(LMUL))
                                }
                                DUP, DUP_X1, DUP_X2, DUP2_X1, DUP2_X2 -> error(insn)
                                else -> insnList.insertBeforeSafe(insn, LdcInsnNode(decoder), InsnNode(LMUL))
                            }
                        }
                        else -> error(insn)
                    }
                }
            }
        }
    }

    private fun solveConstantMath(c: ClassNode, m: MethodNode) {
        val insnList = m.instructions
        val interpreter = Inter()
        val analyzer = Analyzer(interpreter)
        analyzer.analyze(c.name, m)
        for (mul in interpreter.constantMultiplications) {
            when (mul.insn.opcode) {
                IMUL -> associateMultiplication(insnList, mul, 1)
                LMUL -> associateMultiplication(insnList, mul, 1L)
                else -> error(mul)
            }
        }
    }

    private fun associateMultiplication(insnList: InsnList, mul: Expr.Mul, num: Int) {
        val n = num * mul.const.n.toInt()
        val other = mul.other
        when {
            other is Expr.Mul -> {
                insnList.removeSafe(mul.insn, mul.const.insn)
                associateMultiplication(insnList, other, n)
            }
            other is Expr.Const -> {
                insnList.removeSafe(mul.insn, mul.const.insn)
                insnList.setSafe(other.insn, loadInt(n * other.n.toInt()))
            }
            other is Expr.Add -> {
                insnList.removeSafe(mul.insn, mul.const.insn)
                distributeAddition(insnList, other.a, n)
                distributeAddition(insnList, other.b, n)
            }
            n == 1 -> insnList.removeSafe(mul.insn, mul.const.insn)
            else -> insnList.setSafe(mul.const.insn, loadInt(n))
        }
    }

    private fun associateMultiplication(insnList: InsnList, mul: Expr.Mul, num: Long) {
        val n = num * mul.const.n.toLong()
        val other = mul.other
        when {
            other is Expr.Mul -> {
                insnList.removeSafe(mul.insn, mul.const.insn)
                associateMultiplication(insnList, other, n)
            }
            other is Expr.Const -> {
                insnList.removeSafe(mul.insn, mul.const.insn)
                insnList.setSafe(other.insn, loadLong(n * other.n.toLong()))
            }
            other is Expr.Add -> {
                insnList.removeSafe(mul.insn, mul.const.insn)
                distributeAddition(insnList, other.a, n)
                distributeAddition(insnList, other.b, n)
            }
            n == 1L -> insnList.removeSafe(mul.insn, mul.const.insn)
            else -> insnList.setSafe(mul.const.insn, loadLong(n))
        }
    }

    private fun distributeAddition(insnList: InsnList, expr: Expr, n: Int) {
        when (expr) {
            is Expr.Const -> insnList.setSafe(expr.insn, loadInt(n * expr.n.toInt()))
            is Expr.Mul -> associateMultiplication(insnList, expr, n)
            else -> error(expr)
        }
    }

    private fun distributeAddition(insnList: InsnList, expr: Expr, n: Long) {
        when (expr) {
            is Expr.Const -> insnList.setSafe(expr.insn, loadLong(n * expr.n.toLong()))
            is Expr.Mul -> associateMultiplication(insnList, expr, n)
            else -> error(expr)
        }
    }

    private class Inter : Interpreter<Expr>(ASM8) {

        private val sourceInterpreter = SourceInterpreter()

        private val mults = LinkedHashMap<AbstractInsnNode, Expr.Mul>()

        override fun binaryOperation(insn: AbstractInsnNode, value1: Expr, value2: Expr): Expr? {
            val bv = sourceInterpreter.binaryOperation(insn, value1.sv, value2.sv) ?: return null
            if (value1 == value2) return Expr.Var(bv)
            return when (insn.opcode) {
                IMUL, LMUL -> {
                    if (value1 !is Expr.Const && value2 !is Expr.Const) {
                        Expr.Var(bv)
                    } else {
                        Expr.Mul(bv, value1, value2).also {
                            mults[insn] = it
                        }
                    }
                }
                IADD, ISUB, LADD, LSUB -> {
                    if ((value1 is Expr.Const || value1 is Expr.Mul) && (value2 is Expr.Const || value2 is Expr.Mul)) {
                        Expr.Add(bv, value1, value2)
                    } else {
                        Expr.Var(bv)
                    }
                }
                else -> Expr.Var(bv)
            }
        }

        override fun copyOperation(insn: AbstractInsnNode, value: Expr): Expr = Expr.Var(sourceInterpreter.copyOperation(insn, value.sv))

        override fun merge(value1: Expr, value2: Expr): Expr {
            if (value1 == value2) {
                return value1
            } else if (value1 is Expr.Mul && value2 is Expr.Mul && value1.insn == value2.insn) {
                if (value1.a == value2.a && value1.a is Expr.Const) {
                    return Expr.Mul(value1.sv, value1.a, merge(value1.b, value2.b)).also { mults[value1.insn] = it }
                } else if (value1.b == value2.b && value1.b is Expr.Const) {
                    return Expr.Mul(value1.sv, merge(value1.a, value2.a), value1.b).also { mults[value1.insn] = it }
                }
            } else if (value1 is Expr.Add && value2 is Expr.Add && value1.insn == value2.insn) {
                if (value1.a == value2.a && value1.a !is Expr.Var) {
                    val bb = merge(value1.b, value2.b)
                    if (bb is Expr.Const || bb is Expr.Mul) {
                        return Expr.Add(value1.sv, value1.a, bb)
                    }
                } else if (value1.b == value2.b && value2.b !is Expr.Var) {
                    val aa = merge(value1.a, value2.a)
                    if (aa is Expr.Const || aa is Expr.Mul) {
                        return Expr.Add(value1.sv, aa, value1.b)
                    }
                }
            }
            if (value1 is Expr.Mul) mults.remove(value1.insn)
            if (value2 is Expr.Mul) mults.remove(value2.insn)
            return Expr.Var(sourceInterpreter.merge(value1.sv, value2.sv))
        }

        override fun naryOperation(insn: AbstractInsnNode, values: MutableList<out Expr>): Expr? {
            return sourceInterpreter.naryOperation(insn, emptyList())?.let { Expr.Var(it) }
        }

        override fun newOperation(insn: AbstractInsnNode): Expr {
            val bv = sourceInterpreter.newOperation(insn)
            return when (insn.opcode) {
                LDC ->  {
                    val cst = (insn as LdcInsnNode).cst
                    when (cst) {
                        is Int, is Long -> Expr.Const(bv, cst as Number)
                        else -> Expr.Var(bv)
                    }
                }
                ICONST_1, LCONST_1 -> Expr.Const(bv, 1)
                ICONST_0, LCONST_0 -> Expr.Const(bv, 0)
                else -> Expr.Var(bv)
            }
        }

        override fun newValue(type: Type?): Expr? {
            return sourceInterpreter.newValue(type)?.let { Expr.Var(it) }
        }

        override fun returnOperation(insn: AbstractInsnNode, value: Expr, expected: Expr) {}

        override fun ternaryOperation(insn: AbstractInsnNode, value1: Expr, value2: Expr, value3: Expr): Expr? = null

        override fun unaryOperation(insn: AbstractInsnNode, value: Expr): Expr? {
            return sourceInterpreter.unaryOperation(insn, value.sv)?.let { Expr.Var(it) }
        }

        val constantMultiplications: Collection<Expr.Mul> get() {
            val ms = LinkedHashSet<Expr.Mul>()
            for (m in mults.values) {
                val other = m.other
                if (other is Expr.Mul) {
                    ms.remove(other)
                }
                if (other is Expr.Add && other.a is Expr.Mul) {
                    ms.remove(other.a)
                }
                if (other is Expr.Add && other.b is Expr.Mul) {
                    ms.remove(other.b)
                }
                ms.add(m)
            }
            return ms
        }
    }

    private sealed class Expr : Value {

        override fun getSize(): Int = sv.size

        abstract val sv: SourceValue

        val insn get() = sv.insns.single()

        data class Var(override val sv: SourceValue) : Expr() {

            override fun toString(): String = "(#${sv.hashCode().toString(16)})"
        }

        data class Const(override val sv: SourceValue, val n: Number) : Expr() {

            override fun toString(): String ="($n)"
        }

        data class Add(override val sv: SourceValue, val a: Expr, val b: Expr) : Expr() {

            override fun toString(): String {
                val c = if (insn.opcode == IADD || insn.opcode == LADD) '+' else '-'
                return "($a$c$b)"
            }
        }

        data class Mul(override val sv: SourceValue, val a: Expr, val b: Expr) : Expr() {

            val const get() = a as? Const ?: b as Const

            val other get() = if (const == a) b else a

            override fun toString(): String = "($a*$b)"
        }
    }

    fun InsnList.insertSafe(previousInsn: AbstractInsnNode, vararg insns: AbstractInsnNode) {
        check(contains(previousInsn))
        insns.reversed().forEach { insert(previousInsn, it) }
    }

    fun InsnList.insertBeforeSafe(nextInsn: AbstractInsnNode, vararg insns: AbstractInsnNode) {
        check(contains(nextInsn))
        insns.forEach { insertBefore(nextInsn, it) }
    }

    fun InsnList.removeSafe(vararg insns: AbstractInsnNode) {
        insns.forEach {
            check(contains(it))
            remove(it)
        }
    }

    fun InsnList.setSafe(oldInsn: AbstractInsnNode, newInsn: AbstractInsnNode) {
        check(contains(oldInsn))
        set(oldInsn, newInsn)
    }

    fun loadInt(n: Int): AbstractInsnNode = when (n) {
        in -1..5 -> InsnNode(n + 3)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(BIPUSH, n)
        in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(SIPUSH, n)
        else -> LdcInsnNode(n)
    }

    fun loadLong(n: Long): AbstractInsnNode = when (n) {
        0L, 1L -> InsnNode((n + 9).toInt())
        else -> LdcInsnNode(n)
    }

    val AbstractInsnNode.isIntValue: Boolean get() {
        return when (opcode) {
            LDC -> (this as LdcInsnNode).cst is Int
            SIPUSH, BIPUSH, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5, ICONST_M1 -> true
            else -> false
        }
    }

    val AbstractInsnNode.intValue: Int get() {
        if (opcode in 2..8) return opcode - 3
        if (opcode == BIPUSH || opcode == SIPUSH) return (this as IntInsnNode).operand
        if (this is LdcInsnNode && cst is Int) return cst as Int
        error(this)
    }
}