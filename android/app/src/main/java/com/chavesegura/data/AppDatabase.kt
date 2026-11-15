package com.chavesegura.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CredentialEntity::class,
        CategoryEntity::class,
        VaultMetadataEntity::class,
        TrustedDeviceEntity::class,
        SecurityEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao

    abstract fun categories(): CategoryDao

    abstract fun vaultMetadata(): VaultMetadataDao

    abstract fun trustedDevices(): TrustedDeviceDao

    abstract fun securityEvents(): SecurityEventDao

    companion object {
        const val FILE_NAME = "chave_segura.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME).build()

        val DEFAULT_CATEGORIES = listOf(
            CategoryEntity("bancos", "Bancos e finanças", "🏦", sensitiveDefault = true),
            CategoryEntity("email", "E-mail", "✉️", sensitiveDefault = false),
            CategoryEntity("redes", "Redes sociais", "💬", sensitiveDefault = false),
            CategoryEntity("compras", "Compras", "🛒", sensitiveDefault = false),
            CategoryEntity("governo", "Governo e saúde", "🏛️", sensitiveDefault = true),
            CategoryEntity("servicos", "Serviços", "🧰", sensitiveDefault = false),
            CategoryEntity("outros", "Outros", "🔑", sensitiveDefault = false),
        )
    }
}
