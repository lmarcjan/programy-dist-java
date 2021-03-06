import akka.actor.AbstractLoggingActor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.japi.pf.ReceiveBuilder
import akka.pattern.Patterns.ask
import akka.util.Timeout
import scala.concurrent.Await
import java.io.File
import java.util.concurrent.TimeUnit

fun main() {

    // messages
    data class InitActor(val neighbourProcs: List<ActorRef>)
    class InitActorCompleted
    class Start
    class GoDetect
    class ParentBack

    // message timeout
    val timeout = Timeout(5, TimeUnit.SECONDS)

    class BfsActor : AbstractLoggingActor() {

        private lateinit var waiting_from: MutableList<ActorRef>
        private var parent: ActorRef? = null

        override fun createReceive() =
                ReceiveBuilder()
                        .match(InitActor::class.java) { handleInitActor(it) }
                        .match(Start::class.java) { handleStart(it) }
                        .match(GoDetect::class.java) { handleGoDetect(it) }
                        .match(ParentBack::class.java) { handleParentBack(it) }
                        .build()

        fun handleInitActor(init: InitActor) {
            log().debug("Received init actor {} at {} from {}", init, self().path().name(), sender.path().name())
            this.waiting_from = init.neighbourProcs.toMutableList()
            sender.tell(InitActorCompleted(), self())
        }

        fun handleStart(start: Start) {
            log().debug("Received start {} at {} from {}", start, self().path().name(), sender.path().name())
            if (parent != null) {
                log().debug("Ignoring start at {}", self().path().name())
            } else {
                log().info("Setting root at {}", self().path().name())
                parent = self()
                waiting_from.forEach {
                    it.tell(GoDetect(), self())
                }
            }
        }

        fun handleGoDetect(goDetect: GoDetect) {
            log().debug("Received go detect {} at {} from {}", goDetect, self().path().name(), sender.path().name())
            if (parent == null) {
                log().info("Setting parent at {} from {}", self().path().name(), sender.path().name())
                parent = sender()
                if (waiting_from.isEmpty()) {
                    sender.tell(ParentBack(), self())
                } else {
                    waiting_from.forEach {
                        it.tell(GoDetect(), self())
                    }
                }
            }
        }

        fun handleParentBack(parentBack: ParentBack) {
            log().debug("Received parent back {} at {} from {}", parentBack, self().path().name(), sender.path().name())
            waiting_from.remove(sender)
        }

    }

    val system = ActorSystem.create("BfsSystem")
    val adj = GraphUtil.readGraphAdj(File(BfsActor::class.java.getResource("graph-floyd-warshall.adj").file))
    val n = adj.size
    val actors = mutableMapOf<Int, ActorRef>()
    for (i in 0 until n) {
        val actor = system.actorOf(Props.create(BfsActor::class.java), "" + i)
        actors.put(i, actor)
    }

    val graph = mutableMapOf<ActorRef, List<ActorRef>>()
    for ((i, actorRef) in actors) {
        val actorAdj = adj.get(i).map { actors.get(it) }.filterNotNull()
        graph.put(actorRef, actorAdj)
    }

    graph.forEach { node, nbs ->
        val future = ask(node, InitActor(nbs), timeout.duration().toMillis())
        Await.result(future, timeout.duration())
    }

    actors.get(0)?.tell(Start(), ActorRef.noSender())

    Thread.sleep(timeout.duration().toMillis())

    system.terminate()

}