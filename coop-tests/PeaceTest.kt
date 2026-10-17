import com.aitddemo.*
import com.aitddemo.game.*

// Co-op prototype on the PC engine: after startCoop() the attic is peaceful (no creature in six minutes of standing still) and the stairs are closed.
fun main() {
    ui = EventUi(); game = loadGame(ui); tt = 0
    for (i in 0 until 40) tick()
    game.startCoop(false)
    println("coop=${game.coop}; creature A active=${game.objects[AtticGame.ATTIC_CREATURE_A].w.active}, B active=${game.objects[AtticGame.ATTIC_CREATURE_B].w.active}")
    var hurt = false
    for (t in 0 until 60 * 60 * 6) {
        tick()
        if (game.vm.vars[21].toInt() < 20) { hurt = true; println("hurt at ${t / 60} s"); break }
        if (game.gameOver) break
    }
    println("six minutes idle: life ${game.vm.vars[21]}, hurt=$hurt, gameOver=${game.gameOver}, A active=${game.objects[AtticGame.ATTIC_CREATURE_A].w.active}, B active=${game.objects[AtticGame.ATTIC_CREATURE_B].w.active}")
    // to the stairs
    val z = game.roomView.zones.first { it.type == 10 }
    walkTo("the stairs", (z.x1 + z.x2) / 2, (z.z1 + z.z2) / 2, 250)
    for (t in 0 until 600) tick()
    println("after 10 s at the stairs: stage ${game.roomView.stage} room ${game.roomView.room}, stairs notice tick ${game.coopStairsTick}, now ${game.time}, control var0=${game.vm.vars[0]}")
}
