package com.chavesegura.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CredentialEntity::class,
        CategoryEntity::class,
        VaultMetadataEntity::class,
        TrustedDeviceEntity::class,
        SecurityEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao

    abstract fun categories(): CategoryDao

    abstract fun vaultMetadata(): VaultMetadataDao

    abstract fun trustedDevices(): TrustedDeviceDao

    abstract fun securityEvents(): SecurityEventDao

    companion object {
        const val FILE_NAME = "chave_segura.db"

        val DEFAULT_CATEGORIES = listOf(
            CategoryEntity("bancos", "Bancos e finanças", "🏦", sensitiveDefault = true),
            CategoryEntity("email", "E-mail", "✉️", sensitiveDefault = false),
            CategoryEntity("redes", "Redes sociais", "💬", sensitiveDefault = false),
            CategoryEntity("compras", "Compras", "🛒", sensitiveDefault = false),
            CategoryEntity("governo", "Governo e saúde", "🏛️", sensitiveDefault = true),
            CategoryEntity("servicos", "Serviços", "🧰", sensitiveDefault = false),
            CategoryEntity("outros", "Outros", "🔑", sensitiveDefault = false),
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                seedDefaultCategories(db)
                // Normaliza referências órfãs antes de ativar a chave estrangeira em credentials.
                db.execSQL(
                    "UPDATE credentials SET category_id = NULL " +
                        "WHERE category_id IS NOT NULL AND category_id NOT IN (SELECT id FROM categories)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `credentials_new` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `username_encrypted` BLOB NOT NULL,
                        `password_encrypted` BLOB NOT NULL,
                        `website_domain` TEXT NOT NULL,
                        `category_id` TEXT,
                        `notes_encrypted` BLOB,
                        `sensitive` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE CASCADE ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `credentials_new` (
                        `id`, `title`, `username_encrypted`, `password_encrypted`,
                        `website_domain`, `category_id`, `notes_encrypted`,
                        `sensitive`, `created_at`, `updated_at`
                    )
                    SELECT
                        `id`, `title`, `username_encrypted`, `password_encrypted`,
                        `website_domain`, `category_id`, `notes_encrypted`,
                        `sensitive`, `created_at`, `updated_at`
                    FROM `credentials`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `credentials`")
                db.execSQL("ALTER TABLE `credentials_new` RENAME TO `credentials`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credentials_website_domain` ON `credentials` (`website_domain`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credentials_category_id` ON `credentials` (`category_id`)")

                db.execSQL("ALTER TABLE `trusted_devices` ADD COLUMN `last_tx_seq` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trusted_devices` ADD COLUMN `last_rx_seq` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_devices_link_id` ON `trusted_devices` (`link_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trusted_devices_status` ON `trusted_devices` (`status`)")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_events_device_id` ON `security_events` (`device_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_events_credential_id` ON `security_events` (`credential_id`)")
            }
        }

        private val SEED_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedDefaultCategories(db)
            }
        }

        private fun seedDefaultCategories(db: SupportSQLiteDatabase) {
            DEFAULT_CATEGORIES.forEach { category ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (id, name, icon, sensitive_default) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(
                        category.id,
                        category.name,
                        category.icon,
                        if (category.sensitiveDefault) 1 else 0,
                    ),
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .addCallback(SEED_CALLBACK)
                .build()
    }
}
