package zio.config.actions

import zio.config.Details
import zio.config.ConfigError.MissingValue
import zio.config.{ Config, ConfigError, ConfigReport, ConfigSource }
import zio.{ Ref, ZIO }

case class Read[A] private (value: ZIO[(Ref[ConfigReport], ConfigSource), List[ConfigError], (ConfigReport, A)]) {
  def run: ZIO[ConfigSource, List[ConfigError], (ConfigReport, A)] =
    Ref.make(ConfigReport(Nil)).flatMap(ref => contramap(value, source => (ref, source)))

  final def contramap[R0, E, X, R](self: ZIO[R, E, X], f: R0 => R): ZIO[R0, E, X] =
    ZIO.accessM(r0 => self.provide(f(r0)))
}

object Read {
  // Read
  final def read[A](config: Config[A]): Read[A] =
    config match {
      case Config.Source(path, propertyType) =>
        Read(
          ZIO.accessM[(Ref[ConfigReport], ConfigSource)](
            modules => {
              val configSource = modules._2.configService
              val report       = modules._1

              for {
                values <- configSource
                           .getString(path)
                           .mapError(e => List(ConfigError(Seq(path), ConfigError.Unknown(e))))
                result <- values match {
                           case None =>
                             ZIO.fail(List(ConfigError(Seq(path), MissingValue)))
                           case Some(value) =>
                             report
                               .update(_.addDetails(Details(path, value, propertyType.description)))
                               .flatMap { r =>
                                 ZIO.fromEither(
                                   propertyType
                                     .read(value)
                                     .fold(r => Left(List(ConfigError(Seq(path), r))), e => Right((r, e)))
                                 )
                               }
                         }
              } yield result
            }
          )
        )

      case Config.Sources(propertyType, paths) =>
        paths.toList match {
          case h :: t => read(Config.Source(h, propertyType) | Config.Sources(propertyType, t))
          case Nil    => Read(ZIO.fail(Nil))
        }

      case Config.Map(c, f) => Read(read(c).value.map(t => (t._1, f(t._2))))

      case Config.ErrorMap(c, f) =>
        Read(read(c).value.flatMap(t => ZIO.fromEither(f(t._2)).bimap(tt => List(tt), res => (t._1, res))))

      case Config.OnError(config, f, g) =>
        Read(ZIO.accessM[(Ref[ConfigReport], ConfigSource)](modules => {
          val report = modules._1
          read(config).value.either.flatMap({
            case Right(value) => ZIO.succeed((value._1, g(value._2)))
            case Left(value) =>
              report.get.flatMap[Any, List[ConfigError], (ConfigReport, A)](r => ZIO.succeed((r, f(value))))
          })
        }))

      case Config.Zip(left, right) =>
        Read(
          read(left).value.either
            .flatMap(
              res1 =>
                read(right).value.either.map(
                  res2 =>
                    (res1, res2) match {
                      case (Right((_, a)), Right((report2, b))) => Right((report2, (a, b)))
                      case (Left(a), Right(_))                  => Left(a)
                      case (Right(_), Left(error))              => Left(error)
                      case (Left(err1), Left(err2))             => Left(err1 ++ err2)
                    }
                )
            )
            .absolve
        )

      case Config.OrElseEither(left, right) =>
        Read(
          read(left).value.either.flatMap(
            {
              case Right((report, a)) => ZIO.access(_ => (report, Left(a)))
              case Left(lerr) =>
                read(right).value.either.flatMap(
                  {
                    case Right((report, b)) => ZIO.access(_ => (report, Right(b)))
                    case Left(rerr)         => ZIO.fail(lerr ++ rerr)
                  }
                )
            }
          )
        )

      case Config.Xmap(config, from, _) =>
        Read(read(config).value.map(t => (t._1, from(t._2))))
    }
}