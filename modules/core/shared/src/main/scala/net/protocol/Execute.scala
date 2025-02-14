// Copyright (c) 2018-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.net.protocol

import cats.syntax.all._
import cats.MonadError
import skunk.{~, RedactionStrategy}
import skunk.data.Completion
import skunk.exception.PostgresErrorException
import skunk.net.{ Protocol, MessageSocket }
import skunk.net.message.{ Execute => ExecuteMessage, _ }
import skunk.util.Typer
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Span
import org.typelevel.otel4s.trace.Tracer
import skunk.exception.CopyNotSupportedException
import skunk.exception.EmptyStatementException

trait Execute[F[_]] {
  def apply[A](portal: Protocol.CommandPortal[F, A]): F[Completion]
  def apply[A, B](portal: Protocol.QueryPortal[F, A, B], maxRows: Int, ty: Typer): F[List[B] ~ Boolean]
}

object Execute {

  def apply[F[_]: Exchange: MessageSocket: Tracer](redactionStrategy: RedactionStrategy)(
    implicit ev: MonadError[F, Throwable]
  ): Execute[F] =
    new Unroll[F] with Execute[F] {

      override def apply[A](portal: Protocol.CommandPortal[F, A]): F[Completion] =
        exchange("execute") { (_: Span[F]) =>
          for {
            _  <- send(ExecuteMessage(portal.id.value, 0))
            _  <- send(Flush)
            c  <- flatExpect {
              case CommandComplete(c) => send(Sync) *> expect { case ReadyForQuery(_) => c } // https://github.com/tpolecat/skunk/issues/210

              case EmptyQueryResponse =>
                send(Sync) *>
                expect { case ReadyForQuery(_) => } *>
                new EmptyStatementException(portal.preparedCommand.command).raiseError[F, Completion]

              case CopyOutResponse(_) =>
                receive.iterateUntil {
                  case CommandComplete(_) => true
                  case _                  => false
                } *>
                new CopyNotSupportedException(portal.preparedCommand.command).raiseError[F, Completion]

              case CopyInResponse(_) =>
                send(CopyFail) *>
                expect { case ErrorResponse(_) => } *>
                send(Sync) *>
                expect { case ReadyForQuery(_) => } *>
                new CopyNotSupportedException(portal.preparedCommand.command).raiseError[F, Completion]

              case ErrorResponse(info) =>
                for {
                  hi <- history(Int.MaxValue)
                  _  <- send(Sync)
                  _  <- expect { case ReadyForQuery(_) => }
                  redactedArgs = portal.preparedCommand.command.encoder.types.zip(
                    redactionStrategy.redactArguments(portal.preparedCommand.command.encoder.encode(portal.arguments)))
                  a  <- new PostgresErrorException(
                          sql             = portal.preparedCommand.command.sql,
                          sqlOrigin       = Some(portal.preparedCommand.command.origin),
                          info            = info,
                          history         = hi,
                          arguments       = redactedArgs,
                          argumentsOrigin = Some(portal.argumentsOrigin)
                        ).raiseError[F, Completion]
                } yield a
            }
          } yield c
        }

      override def apply[A, B](portal: Protocol.QueryPortal[F, A, B], maxRows: Int, ty: Typer): F[List[B] ~ Boolean] =
        exchange("execute") { (span: Span[F]) =>
          for {
            _  <- span.addAttributes(
                    Attribute("max-rows",    maxRows.toLong),
                    Attribute("portal-id", portal.id.value)
                  )
            _  <- send(ExecuteMessage(portal.id.value, maxRows))
            _  <- send(Flush)
            rs <- unroll(portal)
          } yield rs
        }

    }

}
