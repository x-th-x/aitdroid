package com.aitddemo.life

/**
 * Instruction set of the life scripts (LISTLIFE.PAK) of Alone in the Dark 1992: names by opcode number and the operands of each.
 * Operand letters: s = a 16 bit literal, v = an expression (evalVar), j = a jump offset; MULTI_CASE and DEF_SEQUENCE_SAMPLE have a variable length.
 */
object Aitd1Ops {
    val NAMES = arrayOf(
        "DO_MOVE", "ANIM_ONCE", "ANIM_ALL_ONCE", "BODY", "IF_EGAL", "IF_DIFFERENT", "IF_SUP_EGAL", "IF_SUP", "IF_INF_EGAL", "IF_INF",
        "GOTO", "RETURN", "END", "ANIM_REPEAT", "ANIM_MOVE", "MOVE", "HIT", "MESSAGE", "MESSAGE_VALUE", "VAR",
        "INC", "DEC", "ADD", "SUB", "LIFE_MODE", "SWITCH", "CASE", "CAMERA", "START_CHRONO", "MULTI_CASE",
        "FOUND", "LIFE", "DELETE", "TAKE", "IN_HAND", "READ", "ANIM_SAMPLE", "SPECIAL", "DO_REAL_ZV", "SAMPLE",
        "TYPE", "GAME_OVER", "MANUAL_ROT", "RND_FREQ", "MUSIC", "SET_BETA", "DO_ROT_ZV", "STAGE", "FOUND_NAME", "FOUND_FLAG",
        "FOUND_LIFE", "CAMERA_TARGET", "DROP", "FIRE", "TEST_COL", "FOUND_BODY", "SET_ALPHA", "STOP_BETA", "DO_MAX_ZV", "PUT",
        "C_VAR", "DO_NORMAL_ZV", "DO_CARRE_ZV", "SAMPLE_THEN", "LIGHT", "SHAKING", "INVENTORY", "FOUND_WEIGHT", "UP_COOR_Y", "SPEED",
        "PUT_AT", "DEF_ZV", "HIT_OBJECT", "GET_HARD_CLIP", "ANGLE", "REP_SAMPLE", "THROW", "WATER", "PICTURE", "STOP_SAMPLE",
        "NEXT_MUSIC", "FADE_MUSIC", "STOP_HIT_OBJECT", "COPY_ANGLE", "END_SEQUENCE", "SAMPLE_THEN_REPEAT", "WAIT_GAME_OVER",
    )

    /** Operands of each instruction (see the class comment). */
    val SIG: Map<String, String> = mapOf(
        "DO_MOVE" to "", "ANIM_ONCE" to "ss", "ANIM_ALL_ONCE" to "ss", "BODY" to "v",
        "IF_EGAL" to "vvj", "IF_DIFFERENT" to "vvj", "IF_SUP_EGAL" to "vvj", "IF_SUP" to "vvj", "IF_INF_EGAL" to "vvj", "IF_INF" to "vvj",
        "GOTO" to "j", "RETURN" to "", "END" to "", "ANIM_REPEAT" to "s", "ANIM_MOVE" to "sssssss", "MOVE" to "ss", "HIT" to "ssssvs",
        "MESSAGE" to "s", "MESSAGE_VALUE" to "ss", "VAR" to "sv", "INC" to "s", "DEC" to "s", "ADD" to "sv", "SUB" to "sv",
        "LIFE_MODE" to "s", "SWITCH" to "v", "CASE" to "sj", "CAMERA" to "s", "START_CHRONO" to "", "MULTI_CASE" to "*",
        "FOUND" to "s", "LIFE" to "s", "DELETE" to "s", "TAKE" to "s", "IN_HAND" to "s", "READ" to "sss", "ANIM_SAMPLE" to "vss",
        "SPECIAL" to "s", "DO_REAL_ZV" to "", "SAMPLE" to "v", "TYPE" to "s", "GAME_OVER" to "", "MANUAL_ROT" to "", "RND_FREQ" to "s",
        "MUSIC" to "s", "SET_BETA" to "ss", "DO_ROT_ZV" to "", "STAGE" to "sssss", "FOUND_NAME" to "s", "FOUND_FLAG" to "s",
        "FOUND_LIFE" to "s", "CAMERA_TARGET" to "s", "DROP" to "vs", "FIRE" to "ssssss", "TEST_COL" to "s", "FOUND_BODY" to "s",
        "SET_ALPHA" to "ss", "STOP_BETA" to "", "DO_MAX_ZV" to "", "PUT" to "sssssssss", "C_VAR" to "sv", "DO_NORMAL_ZV" to "",
        "DO_CARRE_ZV" to "", "SAMPLE_THEN" to "vv", "LIGHT" to "s", "SHAKING" to "s", "INVENTORY" to "s", "FOUND_WEIGHT" to "s",
        "UP_COOR_Y" to "", "SPEED" to "s", "PUT_AT" to "ss", "DEF_ZV" to "ssssss", "HIT_OBJECT" to "ss", "GET_HARD_CLIP" to "",
        "ANGLE" to "sss", "REP_SAMPLE" to "vs", "THROW" to "sssssss", "WATER" to "s", "PICTURE" to "sss", "STOP_SAMPLE" to "",
        "NEXT_MUSIC" to "s", "FADE_MUSIC" to "s", "STOP_HIT_OBJECT" to "", "COPY_ANGLE" to "s", "END_SEQUENCE" to "",
        "SAMPLE_THEN_REPEAT" to "vv", "WAIT_GAME_OVER" to "",
    )

    /** Extra literal operands of the expressions (evalVar) by function code (0-based, as the original reads them). */
    fun extraOperands(code: Int): Int = when (code) {
        0x0E, 0x12, 0x1C, 0x20, 0x24, 0x26 -> 1 // DIST(obj), POSREL(obj), RAND(n), OBJECT(obj), C_VAR(n), THROW(obj); ISFOUND (0x10) has a nested expression
        0x22 -> 2 // TEST_ZV_END_ANIM(anim, param)
        else -> 0
    }
}
