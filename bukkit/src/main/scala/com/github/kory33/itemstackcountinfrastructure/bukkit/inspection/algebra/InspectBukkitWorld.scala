package com.github.kory33.itemstackcountinfrastructure.bukkit.inspection.algebra

import cats.Functor
import cats.effect.SyncIO
import com.github.kory33.itemstackcountinfrastructure.core
import com.github.kory33.itemstackcountinfrastructure.core._
import com.github.kory33.itemstackcountinfrastructure.core.algebra.InspectStorages
import com.github.kory33.itemstackcountinfrastructure.minecraft.concurrent.OnMinecraftThread
import org.bukkit.inventory.{Inventory, ItemStack}
import org.bukkit.{Bukkit, Location}

object InspectBukkitWorld {

  def apply[F[_]: OnMinecraftThread: Functor]: InspectStorages[F] =
    new InspectStorages[F] {
      override def at(targets: InspectionTargets): F[InspectionResult] = {
        val worldGrouped: Map[String, List[(Int, Int, Int)]] =
          targets.targets.toList.groupMap(_.worldName)(l => (l.x, l.y, l.z))

        val inspectOnMainThread: F[List[(StorageLocation, Option[Inventory])]] =
          OnMinecraftThread[F].run(SyncIO {
            for {
              (worldName, locs) <- worldGrouped.toList
              world <- Option.apply(Bukkit.getWorld(worldName)).toList
              (x, y, z) <- locs
            } yield {
              val inventorySnapshot = {
                world.getBlockAt(x, y, z).getState match {
                  case state: org.bukkit.block.Container =>
                    Some(state.getSnapshotInventory())
                  case _ => None
                }
              }

              (StorageLocation(worldName, x, y, z), inventorySnapshot)
            }
          })

        Functor[F].map(inspectOnMainThread) { list =>
          core.InspectionResult {
            val resultList: Seq[(StorageLocation, LocationInspectionResult)] = for {
              (location, inventoryOption) <- list
            } yield {
              inventoryOption match {
                case Some(inventorySnapshot) =>
                  val inventoryCollection: Iterable[ItemStack] =
                    new scala.jdk.CollectionConverters.IterableHasAsScala(
                      inventorySnapshot
                    ).asScala

                  val itemAmounts: ItemAmounts =
                    inventoryCollection.groupMap(_.getType.name)(_.getAmount).map {
                      case (name, amounts) =>
                        (ItemStackTypeName.apply(name), amounts.sum)
                    }

                  (location, LocationInspectionResult.Found(itemAmounts))
                case None =>
                  (location, LocationInspectionResult.NoContainerFound)
              }
            }

            resultList.toMap
          }
        }
      }
    }

}
