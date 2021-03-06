package app.interpreter

import java.time.OffsetDateTime

import app.action._
import app.model.{Snapshot, Event}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class MutableMapEventStoreInterpreter(implicit ec: ExecutionContext) extends EventStoreInterpreter {
  val mutableEventMap = mutable.Map[String, Event]()
  val mutableSnapshotMap = mutable.Map[String, Snapshot]()

  implicit def eventOrderInstance: Ordering[OffsetDateTime] = new Ordering[OffsetDateTime] {
    override def compare(x: OffsetDateTime, y: OffsetDateTime): Int = x compareTo y
  }

  override def run[A](eventStoreAction: EventStoreAction[A]): Future[A] = Future {
    eventStoreAction match {
      case SaveEvent(event, next) => mutableEventMap += (event.id.id -> event); next

      case ListEvents(entityId, systemName, pageSize, pageNumber, onResult) => onResult {
        val all: List[Event] = mutableEventMap.toList.map(_._2)
        val entityFiltered: List[Event] = entityId.fold(all)(id => all.filter(_.entityId == id))
        val systemNameFiltered = systemName.fold(entityFiltered)(name => entityFiltered.filter(_.systemName.name == name.name))
        val sorted: List[Event] = systemNameFiltered.sortBy(_.suppliedTimestamp)
        pageNumber.fold(sorted.takeRight(pageSize)) { p =>
          val startIndex = p.toInt * pageSize
          val endIndex = startIndex + pageSize
          sorted.slice(startIndex, endIndex)
        }
      }
      case ListEventsByRange(entityId, systemName, from, to, onResult) =>
        val eventsForEntity = mutableEventMap.filter {
          case (eventId, event) => event.entityId == entityId &&
            from.fold(true)(f => event.suppliedTimestamp.isAfter(f)) &&
            event.suppliedTimestamp.isBefore(to) &&
            event.systemName.name == systemName.name
        }
        val orderedEvents: List[Event] = eventsForEntity.toList.map(_._2).sortBy(_.suppliedTimestamp)
        onResult(orderedEvents)

      case SaveSnapshot(snapshot, next) =>
        mutableSnapshotMap += (snapshot.id.id -> snapshot)
        next

      case GetLatestSnapshot(entityId, systemName, time, onResult) =>
        onResult(mutableSnapshotMap.filter {
          case (id, snapshot) => snapshot.entityId.id == entityId.id &&
            snapshot.systemName.name == systemName.name
        }.toList.map(_._2)
          .sortBy(_.timestamp)
          .reverse
          .find(_.timestamp.isBefore(time)))

      case GetEventsCount(entityId, systemName, onResult) => onResult {
        val events = entityId.fold(mutableEventMap)(id => mutableEventMap.filter { case (key, event) => event.id.id == id.id})
        val filtered = systemName.fold(events)(name => events.filter { case(key,event) => event.systemName == name})
        filtered.size
      }
    }
  }
}
