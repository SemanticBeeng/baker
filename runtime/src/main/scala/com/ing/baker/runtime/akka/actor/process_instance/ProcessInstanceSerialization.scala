package com.ing.baker.runtime.akka.actor.process_instance

import java.security.MessageDigest

import com.ing.baker.petrinet.api._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceEventSourcing._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceSerialization._
import com.ing.baker.runtime.akka.actor.process_instance.internal.ExceptionStrategy.{BlockTransition, RetryWithDelay}
import com.ing.baker.runtime.akka.actor.process_instance.internal.Instance
import com.ing.baker.runtime.akka.actor.process_instance.protobuf.FailureStrategy.StrategyType
import com.ing.baker.runtime.akka.actor.process_instance.protobuf._
import com.ing.baker.runtime.akka.actor.protobuf.{ProducedToken, SerializedData}
import com.ing.baker.runtime.serialization.ProtoMap.{ctxFromProto, ctxToProto}
import com.ing.baker.runtime.serialization.SerializersProvider

import scala.util.{Failure, Success}

object ProcessInstanceSerialization {

  /**
    * TODO:
    *
    * This approach is fragile, the identifier function cannot change ever or recovery breaks
    * a more robust alternative is to generate the ids and persist them
    */
  def tokenIdentifier(tokenValue: Any): Long = tokenValue match {
    case null        => -1
    case str: String => sha256(str)
    case obj         => obj.hashCode()
  }

  def sha256(str: String) = {
    val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    BigInt(1, sha256Digest.digest(str.getBytes("UTF-8"))).toLong
  }
}

/**
  * This class is responsible for translating the EventSourcing.Event to and from the protobuf.Event
  *
  * (which is generated by scalaPB and serializes to protobuf)
  */
class ProcessInstanceSerialization[P : Identifiable, T : Identifiable, S, E](provider: SerializersProvider) {

  implicit private val p: SerializersProvider = provider

  /**
    * De-serializes a persistence.protobuf.Event to a EvenSourcing.Event. An Instance is required to 'wire' or 'reference'
    * the message back into context.
    */
  def deserializeEvent(event: AnyRef): Instance[P, T, S] ⇒ ProcessInstanceEventSourcing.Event = event match {
    case e: protobuf.Initialized ⇒ deserializeInitialized(e)
    case e: protobuf.TransitionFired ⇒ deserializeTransitionFired(e)
    case e: protobuf.TransitionFailed ⇒ deserializeTransitionFailed(e)
  }

  /**
    * Serializes an EventSourcing.Event to a persistence.protobuf.Event.
    */
  def serializeEvent(e: ProcessInstanceEventSourcing.Event): Instance[P, T, S] ⇒ AnyRef =
    _ ⇒ e match {
      case e: InitializedEvent ⇒ serializeInitialized(e)
      case e: TransitionFiredEvent ⇒ serializeTransitionFired(e)
      case e: TransitionFailedEvent ⇒ serializeTransitionFailed(e)
    }

  private def missingFieldException(field: String) = throw new IllegalStateException(s"Missing field in serialized data: $field")

  def serializeObject(obj: Any): Option[SerializedData] = {

    if (obj == null)
      None
    else
      Some(ctxToProto(obj.asInstanceOf[AnyRef]))
  }

  private def deserializeObject(obj: SerializedData): AnyRef = ctxFromProto(obj) match {
    case Success(value) => value.asInstanceOf[AnyRef]
    case Failure(exception) => throw exception
  }

  private def deserializeProducedMarking(instance: Instance[P, T, S], produced: Seq[ProducedToken]): Marking[Id] = {
    produced.foldLeft(Marking.empty[Long]) {
      case (accumulated, ProducedToken(Some(placeId), Some(_), Some(count), data)) ⇒
        val value = data.map(deserializeObject).orNull // In the colored petrinet, tokens have values and they could be null.
        accumulated.add(placeId, value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ProducedToken")
    }
  }

  private def serializeProducedMarking(produced: Marking[Id]): Seq[ProducedToken] = {
    produced.toSeq.flatMap {
      case (placeId, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ ProducedToken(
          placeId = Some(placeId),
          tokenId = Some(tokenIdentifier(value)),
          count = Some(count),
          tokenData = serializeObject(value)
        )
      }
    }
  }

  private def serializeConsumedMarking(m: Marking[Id]): Seq[protobuf.ConsumedToken] =
    m.toSeq.flatMap {
      case (placeId, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ protobuf.ConsumedToken(
          placeId = Some(placeId),
          tokenId = Some(tokenIdentifier(value)),
          count = Some(count)
        )
      }
    }

  private def deserializeConsumedMarking(instance: Instance[P, T, S], persisted: Seq[protobuf.ConsumedToken]): Marking[Id] = {
    persisted.foldLeft(Marking.empty[Long]) {
      case (accumulated, protobuf.ConsumedToken(Some(placeId), Some(tokenId), Some(count))) ⇒
        val place = instance.petriNet.places.getById(placeId, "place in the petrinet")
        val keySet = instance.marking(place).keySet
        val value = keySet.find(e ⇒ tokenIdentifier(e) == tokenId).getOrElse(
          throw new IllegalStateException(s"Missing token with id $tokenId, keySet = ${keySet.map(tokenIdentifier)}")
        )
        accumulated.add(placeId, value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ConsumedToken")
    }
  }

  private def deserializeInitialized(e: protobuf.Initialized)(instance: Instance[P, T, S]): InitializedEvent = {
    val initialMarking = deserializeProducedMarking(instance, e.initialMarking)
    // TODO not really safe to return null here, throw exception ?
    val initialState = e.initialState.map(deserializeObject).orNull
    InitializedEvent(initialMarking, initialState)
  }

  private def serializeInitialized(e: InitializedEvent): protobuf.Initialized = {
    val initialMarking = serializeProducedMarking(e.marking)
    val initialState = serializeObject(e.state)
    protobuf.Initialized(initialMarking, initialState)
  }

  private def deserializeTransitionFailed(e: protobuf.TransitionFailed): Instance[P, T, S] ⇒ TransitionFailedEvent = {
    instance ⇒

      val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
      val transitionId = e.transitionId.getOrElse(missingFieldException("transition_id"))
      val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
      val timeFailed = e.timeFailed.getOrElse(missingFieldException("time_failed"))
      val input = e.inputData.map(deserializeObject).orNull
      val failureReason = e.failureReason.getOrElse("")
      val consumed = deserializeConsumedMarking(instance, e.consumed)
      val failureStrategy = e.failureStrategy.getOrElse(missingFieldException("time_failed")) match {
        case FailureStrategy(Some(StrategyType.BLOCK_TRANSITION), _) ⇒ BlockTransition
        case FailureStrategy(Some(StrategyType.RETRY), Some(delay)) ⇒ RetryWithDelay(delay)
        case other@_ ⇒ throw new IllegalStateException(s"Invalid failure strategy: $other")
      }

      TransitionFailedEvent(jobId, transitionId, e.correlationId, timeStarted, timeFailed, consumed, input, failureReason, failureStrategy)
  }

  private def serializeTransitionFailed(e: TransitionFailedEvent): protobuf.TransitionFailed = {

    val strategy = e.exceptionStrategy match {
      case BlockTransition ⇒ FailureStrategy(Some(StrategyType.BLOCK_TRANSITION))
      case RetryWithDelay(delay) ⇒ FailureStrategy(Some(StrategyType.RETRY), Some(delay))
      case _ => throw new IllegalArgumentException("Unsupported exception strategy")
    }

    protobuf.TransitionFailed(
      jobId = Some(e.jobId),
      transitionId = Some(e.transitionId),
      timeStarted = Some(e.timeStarted),
      timeFailed = Some(e.timeFailed),
      inputData = serializeObject(e.input),
      failureReason = Some(e.failureReason),
      failureStrategy = Some(strategy),
      consumed = serializeConsumedMarking(e.consume)
    )
  }

  private def serializeTransitionFired(e: TransitionFiredEvent): protobuf.TransitionFired = {

    val consumedTokens = serializeConsumedMarking(e.consumed)
    val producedTokens = serializeProducedMarking(e.produced)

    protobuf.TransitionFired(
      jobId = Some(e.jobId),
      transitionId = Some(e.transitionId),
      timeStarted = Some(e.timeStarted),
      timeCompleted = Some(e.timeCompleted),
      consumed = consumedTokens,
      produced = producedTokens,
      data = serializeObject(e.output)
    )
  }

  private def deserializeTransitionFired(e: protobuf.TransitionFired): Instance[P, T, S] ⇒ TransitionFiredEvent = instance ⇒ {

    val consumed: Marking[Id] = deserializeConsumedMarking(instance, e.consumed)
    val produced: Marking[Id] = deserializeProducedMarking(instance, e.produced)

    val output = e.data.map(deserializeObject).orNull

    val transitionId = e.transitionId.getOrElse(missingFieldException("transition_id"))
    val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
    val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
    val timeCompleted = e.timeCompleted.getOrElse(missingFieldException("time_completed"))

    TransitionFiredEvent(jobId, transitionId, e.correlationId, timeStarted, timeCompleted, consumed, produced, output)
  }
}
