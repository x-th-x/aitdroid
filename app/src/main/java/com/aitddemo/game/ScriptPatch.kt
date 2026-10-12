package com.aitddemo.game

import com.aitddemo.life.Aitd1Ops

/**
 * Changes single instructions of a life script (a copy is made). The script is walked instruction by instruction as the interpreter does, so the edit function
 * sees each instruction with the position of its first operand. Edits must keep the length of the instruction (jump offsets of the scripts are relative).
 */
object ScriptPatch {
    /** Called for every instruction: its [name], whether it has an object prefix ([ow] = that object, else -1), and the index of its first operand in [w]; may change [w]. */
    fun interface Edit {
        fun edit(name: String, ow: Int, start: Int, w: ShortArray)
    }

    fun patch(src: ShortArray, edit: Edit): ShortArray {
        val w = src.copyOf()
        var p = 0
        try {
            while (p < w.size) {
                val op = w[p].toInt()
                val first = p
                p++
                var ow = -1
                if (op and 0x8000 != 0) { ow = w[p].toInt(); p++ }
                val name = Aitd1Ops.NAMES.getOrNull(op and 0x7FFF) ?: break
                val sig = Aitd1Ops.SIG[name] ?: break
                val start = p
                if (sig == "*") {
                    if (name == "MULTI_CASE") { val c = w[p].toInt(); p += 1 + c + 1 }
                } else {
                    for (ch in sig) p = if (ch == 'v') skipExpression(w, p) else p + 1
                }
                edit.edit(name, ow, start, w)
                if (name == "DEF_SEQUENCE_SAMPLE") break
                if (p <= first) break
            }
        } catch (_: IndexOutOfBoundsException) {
            // a script that ends inside an instruction is left as far as it was changed
        }
        return w
    }

    private fun skipExpression(w: ShortArray, start: Int): Int {
        var p = start
        val v = w[p++].toInt()
        if (v == -1 || v == 0) return p + 1
        if (v and 0x8000 != 0) p++
        val code = (v and 0x7FFF) - 1
        return if (code == 0x10) skipExpression(w, p) else p + Aitd1Ops.extraOperands(code)
    }

    /** The opcode number of an instruction name. */
    fun opcode(name: String): Short = Aitd1Ops.NAMES.indexOf(name).toShort()
}
