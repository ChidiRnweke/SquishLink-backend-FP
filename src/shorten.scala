package Shorten
import cats.data.Kleisli
import cats.effect._
import cats.effect.std.Env
import cats.effect.std.Random
import cats.syntax.all._
import doobie._
import doobie.implicits._
import fs2.Stream

import java.io.File

case class Link(link: String)
case class RandomLink(adjective: String, noun: String, number: Int):
  override def toString: String = s"$adjective$noun$number"

enum ShortenedLink:
  case FoundLink(link: String)
  case NotFoundLink(link: String)

enum InputLink:
  case ValidInputLink(link: String)
  case InvalidInputLink(link: String)

trait Repository:
  val rootURL: String
  def validateUniqueness(input: RandomLink): IO[Boolean]
  def storeInDatabase(original: String, link: RandomLink): IO[Unit]
  def findInDatabase(shortenedURL: String): IO[ShortenedLink]

case class DoobieRepository(xa: Transactor[IO], rootURL: String)
    extends Repository:
  import ShortenedLink._

  def storeInDatabase(original: String, link: RandomLink): IO[Unit] =
    insertQuery(original, rootURL, link).transact(xa)

  def findInDatabase(shortenedURL: String): IO[ShortenedLink] =
    findQuery(shortenedURL)

  def validateUniqueness(input: RandomLink): IO[Boolean] =
    existenceQuery(input)

  private def existenceQuery(link: RandomLink): IO[Boolean] =
    val query =
      sql"""
    select not exists (
      select 1
      from links 
      where number = ${link.number}
      and adjective = ${link.adjective}  
      and noun = ${link.noun})""".query[Boolean].unique
    query.transact(xa)

  private def insertQuery(
      original: String,
      url: String,
      link: RandomLink
  ): ConnectionIO[Unit] =
    val uri = link.toString
    sql"""insert into links (original_url, url, adjective, noun, number) values 
      ($original, ${uri}, ${link.adjective}, ${link.noun}, ${link.number})""".update.run.void

  private def findQuery(shortenedURL: String): IO[ShortenedLink] =
    sql"""select original_url from links where url = $shortenedURL"""
      .query[String]
      .option
      .map(_.fold(NotFoundLink(shortenedURL))(FoundLink(_)))
      .transact(xa)

object NameGenerator:
  import InputLink._

  def shorten(input: String): Kleisli[IO, Repository, Link] =
    Kleisli: db =>
      for
        randomName <- generateName(db)
        _ <- db.storeInDatabase(input, randomName)
      yield Link(db.rootURL ++ randomName.toString())

  def validateInput(input: String): InputLink =
    input match
      case str if str.isEmpty() => InvalidInputLink(str)
      case s"http://$rest"      => ValidInputLink(s"http://$rest")
      case s"https://$rest"     => ValidInputLink(s"https://$rest")
      case s"www.$rest"         => ValidInputLink(s"https://$rest")
      case rest                 => ValidInputLink(s"https://$rest")

  private def linesFromFile(path: String): IO[List[String]] =
    fs2.io.file
      .Files[IO]
      .readAll(fs2.io.file.Path(path))
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .compile
      .toList

  private def constructName(
      adjective: String,
      noun: String,
      number: Int
  ): RandomLink =
    val positiveNumber = Math.abs(number)
    RandomLink(adjective, noun, positiveNumber)

  private val adjectivesList = linesFromFile("src/data/adjectives.txt")
  private val nounsList = linesFromFile("src/data/animals.txt")

  private def generateRandomName: IO[RandomLink] =
    for
      rng <- Random.scalaUtilRandom[IO]
      adjectives <- adjectivesList
      nouns <- nounsList
      noun <- rng.elementOf(nouns)
      adjective <- rng.elementOf(adjectives)
      number <- rng.nextIntBounded(100)
    yield constructName(adjective, noun, number)

  private def generateName(db: Repository, tries: Int = 10): IO[RandomLink] =
    val errorMsg = Exception("Did not find a unique name within 10 sequences")

    generateRandomName.flatMap: suggestion =>
      db.validateUniqueness(suggestion)
        .flatMap:
          case true               => IO.pure(suggestion)
          case false if tries > 1 => generateName(db, tries - 1)
          case _                  => IO.raiseError(errorMsg)
