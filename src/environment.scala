package Config

import cats.effect._
import cats.effect.std.{Random, Env}
import doobie._
import doobie.implicits._
import cats.syntax.all._

case class DbConfig(driver: String, url: String, user: String, password: String)
case class AppConfig(dbConfig: DbConfig, rootUrl: String)

object Environment:
  private def getEnv(name: String): IO[String] =
    val exception = Exception(s"Environment variable $name was not present")
    Env[IO].get(name).flatMap(_.liftTo[IO](exception))

  private val userName = getEnv("POSTGRES_USER")
  private val password = getEnv("POSTGRES_PASSWORD")
  private val databaseName = getEnv("POSTGRES_DB")
  private val pgPort = getEnv("POSTGRES_PORT")
  private val pgHost = getEnv("POSTGRES_HOST")
  private val root = getEnv("ROOT_URL")

  def loadConfig: IO[AppConfig] = for
    driver <- getEnv("POSTGRES_DRIVER").orElse(IO.pure("org.postgresql.Driver"))
    user <- userName
    password <- password
    rootUrl <- root
    DbName <- databaseName
    host <- pgHost
    port <- pgPort
    url = s"jdbc:postgresql://$host:$port/$DbName"
    db = DbConfig(driver, url, user, password)
  yield AppConfig(db, rootUrl)

object AppResources:
  def makeTransactor(cfg: DbConfig): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = cfg.driver,
      url = cfg.url,
      user = cfg.user,
      password = cfg.password,
      None
    )
