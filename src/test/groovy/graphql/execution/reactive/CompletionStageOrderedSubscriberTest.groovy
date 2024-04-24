package graphql.execution.reactive

import graphql.execution.pubsub.CapturingSubscriber
import graphql.execution.pubsub.CapturingSubscription
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Function

class CompletionStageOrderedSubscriberTest extends Specification {

    def "basic test of mapping"() {
        def capturingSubscriber = new CapturingSubscriber<>()
        def subscription = new CapturingSubscription()
        def mapper = new Function<Integer, CompletionStage<String>>() {
            @Override
            CompletionStage<String> apply(Integer integer) {
                return CompletableFuture.completedFuture(String.valueOf(integer))
            }
        }

        when:
        def completionStageSubscriber = new CompletionStageOrderedSubscriber<Integer, String>(mapper, capturingSubscriber)

        then:
        completionStageSubscriber.getDownstreamSubscriber() == capturingSubscriber

        when:
        completionStageSubscriber.onSubscribe(subscription)
        completionStageSubscriber.onNext(0)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == ["0"]

        when:
        completionStageSubscriber.onNext(1)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == ["0", "1"]

        when:
        completionStageSubscriber.onComplete()

        then:
        !subscription.isCancelled()
        capturingSubscriber.isCompleted()
        capturingSubscriber.events == ["0", "1"]
    }

    def "can hold CFs that have not completed and does not emit them even when onComplete is called"() {
        def capturingSubscriber = new CapturingSubscriber<>()
        def subscription = new CapturingSubscription()
        List<Runnable> promises = []
        Function<Integer, CompletionStage<String>> mapper = mapperThatDoesNotComplete(promises)
        def completionStageSubscriber = new CompletionStageOrderedSubscriber<Integer, String>(mapper, capturingSubscriber)

        when:
        completionStageSubscriber.onSubscribe(subscription)
        completionStageSubscriber.onNext(0)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == []

        when:
        completionStageSubscriber.onNext(1)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == []

        when:
        completionStageSubscriber.onComplete()

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == []

        when:
        promises.forEach { it.run() }

        then:
        !subscription.isCancelled()
        capturingSubscriber.isCompleted()
        capturingSubscriber.events == ["0", "1"]
    }

    def "can hold CFs that have not completed but finishes quickly when onError is called"() {
        def capturingSubscriber = new CapturingSubscriber<>()
        def subscription = new CapturingSubscription()
        List<Runnable> promises = []
        Function<Integer, CompletionStage<String>> mapper = mapperThatDoesNotComplete(promises)
        def completionStageSubscriber = new CompletionStageOrderedSubscriber<Integer, String>(mapper, capturingSubscriber)

        when:
        completionStageSubscriber.onSubscribe(subscription)
        completionStageSubscriber.onNext(0)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == []

        when:
        completionStageSubscriber.onNext(1)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == []

        when:
        completionStageSubscriber.onError(new RuntimeException("Bang"))

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.isCompletedExceptionally()
        // it immediately errored out
        capturingSubscriber.getThrowable().getMessage() == "Bang"
        capturingSubscriber.events == []

        when:
        // even if the promises later complete we are done
        promises.forEach { it.run() }

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.isCompletedExceptionally()
        capturingSubscriber.getThrowable().getMessage() == "Bang"
        capturingSubscriber.events == []
    }

    def "emits values in the order they arrive not the order they complete"() {
        def capturingSubscriber = new CapturingSubscriber<>()
        def subscription = new CapturingSubscription()
        List<Runnable> promises = []
        Function<Integer, CompletionStage<String>> mapper = mapperThatDoesNotComplete(promises)
        def completionStageSubscriber = new CompletionStageOrderedSubscriber<Integer, String>(mapper, capturingSubscriber)

        when:
        completionStageSubscriber.onSubscribe(subscription)
        completionStageSubscriber.onNext(0)
        completionStageSubscriber.onNext(1)
        completionStageSubscriber.onNext(2)
        completionStageSubscriber.onNext(3)

        then:
        !subscription.isCancelled()
        !capturingSubscriber.isCompleted()
        capturingSubscriber.events == []

        when:
        completionStageSubscriber.onComplete()
        promises.reverse().forEach { it.run() }

        then:
        !subscription.isCancelled()
        capturingSubscriber.isCompleted()
        capturingSubscriber.events == ["0","1","2","3"]
    }

    private static Function<Integer, CompletionStage<String>> mapperThatDoesNotComplete(List<Runnable> promises) {
        def mapper = new Function<Integer, CompletionStage<String>>() {
            @Override
            CompletionStage<String> apply(Integer integer) {
                def cf = new CompletableFuture<String>()
                promises.add({ cf.complete(String.valueOf(integer)) })
                return cf
            }
        }
        mapper
    }

}
