package com.aitddemo.life

/**
 * A static look at the life scripts (no script is run): which variables a script writes and which scripts it hands to objects. The co-op mode uses it to find out which
 * objects are creatures (their own scripts change the creature count) and which variables are the player's own state (the ones the player's script writes).
 */
object LifeScan {
    /** [lifeSets]: pairs (object the life goes to, or -1 for the script's own object; script number). [varWrites]: the variables the script changes. */
    class Facts(val lifeSets: List<IntArray>, val varWrites: Set<Int>, val varReads: Set<Int> = emptySet())

    /** Reads a script to its end; null when it does not decode exactly to the end (an op that is not known). */
    fun decode(w: ShortArray): Facts? {
        var p = 0
        val lifeSets = ArrayList<IntArray>()
        val writes = HashSet<Int>()
        val reads = HashSet<Int>()
        fun s16(): Int = if (p < w.size) w[p++].toInt() else { p = Int.MAX_VALUE / 2; 0 }
        fun skipEval() {
            val v = s16()
            if (v == -1) { s16(); return }
            if (v == 0) { reads.add(s16()); return }
            if (v and 0x8000 != 0) s16()
            val code = (v and 0x7FFF) - 1
            if (code == 0x10) skipEval() else repeat(Aitd1Ops.extraOperands(code)) { s16() }
        }
        while (p < w.size) {
            val op = s16()
            var target = -1
            if (op and 0x8000 != 0) target = s16()
            val name = Aitd1Ops.NAMES.getOrNull(op and 0x7FFF) ?: return null
            if (name == "MULTI_CASE") {
                val n = s16()
                repeat(n) { s16() }
                s16()
                continue
            }
            val sig = Aitd1Ops.SIG[name] ?: return null
            val operands = IntArray(sig.length)
            for ((i, c) in sig.withIndex()) {
                when (c) {
                    's', 'j' -> operands[i] = s16()
                    'v' -> skipEval()
                }
            }
            when (name) {
                "VAR", "INC", "DEC", "ADD", "SUB" -> writes.add(operands[0])
                "LIFE" -> lifeSets.add(intArrayOf(target, operands[0]))
            }
        }
        return if (p == w.size) Facts(lifeSets, writes, reads) else null
    }

    /**
     * The objects whose own scripts change variable [countVar] (the creature count: a creature adds one when it enters and takes one off when it dies). An object's own scripts are
     * the one it starts with, the ones any script hands it (obj.life(n)), and the ones those switch to with life(n).
     */
    fun creatureObjects(objects: List<WorldObject>, script: (Int) -> ShortArray?, countVar: Int): Set<Int> {
        val facts = HashMap<Int, Facts>()
        var n = 0
        while (true) {
            val w = script(n) ?: break
            decode(w)?.let { facts[n] = it }
            n++
        }
        val handedTo = HashMap<Int, MutableSet<Int>>() // object -> scripts handed to it by other scripts
        for ((_, f) in facts) for (s in f.lifeSets) if (s[0] >= 0) handedTo.getOrPut(s[0]) { HashSet() }.add(s[1])
        val out = HashSet<Int>()
        for (o in objects) {
            val seen = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            if (o.life >= 0) queue.add(o.life)
            handedTo[o.index]?.let { queue.addAll(it) }
            var touches = false
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (!seen.add(s)) continue
                val f = facts[s] ?: continue
                if (countVar in f.varWrites) touches = true
                for (l in f.lifeSets) if (l[0] < 0) queue.add(l[1])
            }
            if (touches) out.add(o.index)
        }
        return out
    }

    /** The variables the scripts of one object (starting from [firstScript]) write: for the player, his own state. */
    fun varsWrittenFrom(firstScript: Int, script: (Int) -> ShortArray?): Set<Int> {
        val out = HashSet<Int>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(firstScript)
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            if (!seen.add(s)) continue
            val f = script(s)?.let { decode(it) } ?: continue
            out.addAll(f.varWrites)
            for (l in f.lifeSets) if (l[0] < 0) queue.add(l[1])
        }
        return out
    }
}
