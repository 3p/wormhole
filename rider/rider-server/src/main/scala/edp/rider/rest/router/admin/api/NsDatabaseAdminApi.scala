/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.rider.rest.router.admin.api

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import edp.rider.common.RiderLogger
import edp.rider.rest.persistence.dal.NsDatabaseDal
import edp.rider.rest.persistence.entities.{NsDatabase, _}
import edp.rider.rest.router.{JsonSerializer, ResponseJson, ResponseSeqJson, SessionClass}
import edp.rider.rest.util.AuthorizationProvider
import edp.rider.rest.util.CommonUtils._
import edp.rider.rest.util.ResponseUtils._
import slick.jdbc.MySQLProfile.api._

import scala.util.{Failure, Success}


class NsDatabaseAdminApi(databaseDal: NsDatabaseDal) extends BaseAdminApiImpl(databaseDal) with RiderLogger with JsonSerializer {
  override def getByIdRoute(route: String): Route = path(route / LongNumber) {
    id =>
      get {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(OK, getHeader(403, session))
            }
            else {
              onComplete(databaseDal.getDs(visible = false, Some(id)).mapTo[Seq[DatabaseInstance]]) {
                case Success(dsSeq) =>
                  riderLogger.info(s"user ${session.userId} select database where id is $id success.")
                  complete(OK, ResponseJson[DatabaseInstance](getHeader(200, session), dsSeq.head))
                case Failure(ex) =>
                  riderLogger.error(s"user ${session.userId} select database where id is $id failed", ex)
                  complete(OK, getHeader(451, ex.getMessage, session))
              }
            }
        }
      }

  }

  def getByFilterRoute(route: String): Route = path(route) {
    get {
      parameter('visible.as[Boolean].?, 'nsInstanceId.as[Long].?, 'nsDatabaseName.as[String].?) {
        (visible, nsInstanceId, nsDatabaseName) =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin")
                complete(OK, getHeader(403, session))
              else {
                (visible, nsInstanceId, nsDatabaseName) match {
                  case (None, Some(id), Some(name)) =>
                    if (namePattern.matcher(name).matches()) {
                      onComplete(databaseDal.findByFilter(database => database.nsInstanceId === id && database.nsDatabase === name.toLowerCase).mapTo[Seq[NsDatabase]]) {
                        case Success(databases) =>
                          if (databases.isEmpty) {
                            riderLogger.info(s"user ${session.userId} check instance $id database $name doesn't exist.")
                            complete(OK, getHeader(200, session))
                          }
                          else {
                            riderLogger.warn(s"user ${session.userId} check instance $id database $name already exists.")
                            complete(OK, getHeader(409, s"$id instance $name database or topic already exists", session))
                          }
                        case Failure(ex) =>
                          riderLogger.error(s"user ${session.userId} check instance $id database $name does exist failed", ex)
                          complete(OK, getHeader(451, ex.getMessage, session))
                      }
                    } else {
                      riderLogger.info(s"user ${session.userId} check database $name format is wrong.")
                      complete(OK, getHeader(402, s"$name format is wrong", session))
                    }
                  case (_, None, None) =>
                    onComplete(databaseDal.getDs(visible.getOrElse(true)).mapTo[Seq[DatabaseInstance]]) {
                      case Success(dsSeq) =>
                        riderLogger.info(s"user ${session.userId} select all $route where active is ${visible.getOrElse(true)} success.")
                        complete(OK, ResponseSeqJson[DatabaseInstance](getHeader(200, session), dsSeq))
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} select all $route where active is ${visible.getOrElse(true)} failed", ex)
                        complete(OK, getHeader(451, ex.getMessage, session))
                    }
                  case (_, _, _) =>
                    riderLogger.error(s"user ${session.userId} request url is not supported.")
                    complete(OK, getHeader(501, session))
                }
              }
          }
      }
    }

  }

  def postRoute(route: String): Route = path(route) {
    post {
      entity(as[SimpleNsDatabase]) {
        simple =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType == "user") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(OK, getHeader(403, session))
              }
              else {
                if (namePattern.matcher(simple.nsDatabase).matches()) {
                  if (isKeyEqualValue(simple.config.getOrElse(""))) {
                    val database = NsDatabase(0, simple.nsDatabase.trim, simple.desc, simple.nsInstanceId, simple.user, simple.pwd, simple.partitions, simple.config, active = true, currentSec, session.userId, currentSec, session.userId)
                    onComplete(databaseDal.insert(database).mapTo[NsDatabase]) {
                      case Success(db) =>
                        riderLogger.info(s"user ${session.userId} insert database success.")
                        onComplete(databaseDal.getDs(visible = false, Some(db.id)).mapTo[Seq[DatabaseInstance]]) {
                          case Success(dsSeq) =>
                            riderLogger.info(s"user ${session.userId} select database where id is ${db.id} success.")
                            complete(OK, ResponseJson[DatabaseInstance](getHeader(200, session), dsSeq.head))
                          case Failure(ex) =>
                            riderLogger.error(s"user ${session.userId} select database where id is ${db.id} failed", ex)
                            complete(OK, getHeader(451, ex.toString, session))
                        }
                      case Failure(ex) =>
                        if (ex.toString.contains("Duplicate entry")) {
                          riderLogger.error(s"user ${session.userId} insert database failed", ex)
                          complete(OK, getHeader(409, s"${simple.nsInstanceId} instance ${simple.nsDatabase} database or topic already exists", session))
                        }
                        else {
                          riderLogger.error(s"user ${session.userId} insert database failed", ex)
                          complete(OK, getHeader(451, ex.toString, session))
                        }
                    }
                  } else {
                    riderLogger.error(s"user ${session.userId} insert database failed caused by config ${simple.config.get} is not json type")
                    complete(OK, getHeader(400, s"${simple.config.get} is not key=value type", session))
                  }
                } else {
                  riderLogger.info(s"user ${session.userId} check database ${simple.nsDatabase} format is wrong.")
                  complete(OK, getHeader(402, s"${simple.nsDatabase} format is wrong", session))
                }
              }
          }
      }
    }

  }


  def putRoute(route: String): Route = path(route) {
    put {
      entity(as[NsDatabase]) {
        database =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(OK, getHeader(403, session))
              }
              else {
                if (namePattern.matcher(database.nsDatabase).matches()) {
                  if (isKeyEqualValue(database.config.getOrElse(""))) {
                    val db = NsDatabase(database.id, database.nsDatabase.trim, database.desc, database.nsInstanceId, database.user, database.pwd, database.partitions, database.config, database.active, database.createTime, database.createBy, currentSec, session.userId)
                    onComplete(databaseDal.update(db)) {
                      case Success(result) =>
                        riderLogger.info(s"user ${session.userId} update database success.")
                        onComplete(databaseDal.getDs(visible = false, Some(db.id)).mapTo[Seq[DatabaseInstance]]) {
                          case Success(dsSeq) =>
                            riderLogger.info(s"user ${session.userId} select database where id is ${db.id} success.")
                            complete(OK, ResponseJson[DatabaseInstance](getHeader(200, session), dsSeq.head))
                          case Failure(ex) =>
                            riderLogger.error(s"user ${session.userId} select database where id is ${db.id} failed", ex)
                            complete(OK, getHeader(451, ex.getMessage, session))
                        }
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} update database failed", ex)
                        complete(OK, getHeader(451, ex.getMessage, session))
                    }
                  } else {
                    riderLogger.error(s"user ${session.userId} update database failed caused by config ${database.config.get} is not json type")
                    complete(OK, getHeader(400, s"${database.config.get} is not key=value type", session))
                  }
                } else {
                  riderLogger.info(s"user ${session.userId} check database ${database.nsDatabase} format is wrong.")
                  complete(OK, getHeader(402, s"${database.nsDatabase} format is wrong", session))
                }
              }
          }
      }
    }

  }

  def getDbByInstanceIdRoute(route: String): Route = path(route / LongNumber / "databases") {
    id =>
      get {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(OK, getHeader(403, session))
            }
            else {
              onComplete(databaseDal.findByFilter(db => db.active === true && db.nsInstanceId === id).mapTo[Seq[NsDatabase]]) {
                case Success(dsSeq) =>
                  riderLogger.info(s"user ${session.userId} select databases where active is true and instance id is $id success.")
                  complete(OK, ResponseSeqJson[NsDatabase](getHeader(200, session), dsSeq.sortBy(_.nsDatabase)))
                case Failure(ex) =>
                  riderLogger.error(s"user ${session.userId} select databases where active is true and instance id is $id failed", ex)
                  complete(OK, getHeader(451, ex.getMessage, session))
              }
            }
        }
      }

  }

  override def deleteRoute(route: String): Route = path(route / LongNumber) {
    id =>
      delete {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(OK, getHeader(403, session))
            }
            else {
              try {
                val result = databaseDal.delete(id)
                if (result._1) {
                  riderLogger.error(s"user ${session.userId} delete database $id success.")
                  complete(OK, getHeader(200, session))
                }
                else complete(OK, getHeader(412, result._2, session))
              } catch {
                case ex: Exception =>
                  riderLogger.error(s"user ${session.userId} delete database $id failed", ex)
                  complete(OK, getHeader(451, session))
              }
            }
        }
      }
  }

}
