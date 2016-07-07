package com.github.nikalaikina.poehali.bot

import java.time.LocalDate

import akka.actor.{ActorRef, FSM}
import akka.pattern.AskSupport
import akka.util.Timeout
import com.github.nikalaikina.poehali.api.Settings
import com.github.nikalaikina.poehali.bot.ChatFsm._
import com.github.nikalaikina.poehali.logic.{Flight, Logic, TripRoute}
import com.github.nikalaikina.poehali.mesagge.Routes
import com.github.nikalaikina.poehali.sp.FlightsProvider

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

class ChatFsm(fp: FlightsProvider, botApi: ActorRef) extends FSM[ChatFsm.State, ChatFsm.Data] with AskSupport {

  startWith(Idle, Uninitialized)

  when(CollectingCities) {
    case Event(AddCity(city), chat: Collecting) =>
      goto(CollectingCities) using Collecting(chat.id, chat.homeCities, city :: chat.cities)

    case Event(Calculate, chat: Collecting) =>
      calc(chat).onComplete {
        case Success(list: List[TripRoute]) =>
          val result: ResultRoutes = ResultRoutes(chat.id, chat.homeCities, list)
          sendResult(chat.id, result)
          goto(Result) using result
        case Failure(e) => println(s"failed: ${e.getMessage}")
      }
      goto(Idle) using Uninitialized
  }

  when(Result) {
    case Event(GetDetails(k), result: ResultRoutes) =>
      getDetails(result.chatId, result.best(k))
      stay()
  }

  implicit val timeout = Timeout(10 seconds)

  def calc(chat: Collecting): Future[List[TripRoute]] = {
    val settings = Settings(chat.homeCities, chat.cities, LocalDate.now(), LocalDate.now().plusMonths(8), 4, 30, 600, Math.min(2, chat.cities.size - 3))
    (Logic.logic(settings, fp) ? Logic.GetRoutees)
      .mapTo[Routes]
      .map(_.routes)
  }

  def getDetails(person: Long, route: TripRoute): String = {
    var s = ""
    for (flight <- route.flights) {
      s = s + s"\n${flightFormat(flight)}"
    }
    s
  }

  def sendResult(person: Long, result: ResultRoutes): Unit = {
    result.best.foreach(route => botApi ! tripFormat(route))
  }


  def flightFormat(flight: Flight): String = {
    s"[${flight.direction.from} -> ${flight.direction.to}\t${flight.date}\t${flight.price}]"
  }

  def tripFormat(route: TripRoute): String = {
    var cities = route.flights.head.direction.from
    for (flight <- route.flights) {
      cities = cities + " -> " + flight.direction.to
    }

    val cost = route.flights.map(_.price).sum

    s"[$cities $cost$$ ${route.firstDate} - ${route.curDate}]"
  }
}

object ChatFsm {
  sealed trait State
  case object Idle extends State
  case object CollectingCities extends State
  case object Result extends State

  sealed trait Data
  case object Uninitialized extends Data
  case class Collecting(id: Int, homeCities: List[String], cities: List[String]) extends Data
  case class ResultRoutes(chatId: Long, homeCities: List[String], routes: List[TripRoute]) extends Data {
    val best: List[TripRoute] = {
      val byCitiesCount = routes.groupBy(_.citiesCount(homeCities))
      byCitiesCount.keys.toList.sortBy(-_).take(3).map(n => {
        byCitiesCount(n)
          .groupBy(_.cities(homeCities))
          .values
          .map(routes => routes.minBy(_.cost))
      }).flatMap(_.toList)
    }
  }
}


sealed trait ChatMessage
case class AddCity(cityId: String) extends ChatMessage
case object Calculate extends ChatMessage
case class GetDetails(k: Int) extends ChatMessage
