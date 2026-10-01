package io.putdotio.android.account

import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InactiveAccountNoticeTest {
    @Test
    fun `active and stranger accounts show no notice`() {
        assertNull(account(status = "active", deletesAt = "2026-10-15T08:00:00").inactiveAccountNotice())
        assertNull(account(status = "stranger").inactiveAccountNotice())
    }

    @Test
    fun `an inactive account carries its deletion date, read as UTC when put_io sends no zone`() {
        assertEquals(
            InactiveAccountNotice.Deactivated(Instant.parse("2026-10-15T08:00:00Z")),
            account(status = "inactive", deletesAt = "2026-10-15T08:00:00").inactiveAccountNotice(),
        )
    }

    @Test
    fun `an inactive account without a usable deletion date still shows the notice`() {
        assertEquals(InactiveAccountNotice.Deactivated(null), account(status = "inactive").inactiveAccountNotice())
        assertEquals(
            InactiveAccountNotice.Deactivated(null),
            account(status = "inactive", deletesAt = "soon").inactiveAccountNotice(),
        )
    }

    @Test
    fun `an inactive family plan member gets the family plan notice`() {
        assertEquals(
            InactiveAccountNotice.FamilyPlanExpired,
            account(status = "inactive", deletesAt = "2026-10-15T08:00:00", subAccount = true).inactiveAccountNotice(),
        )
    }

    @Test
    fun `deletion days count calendar days in the viewer's zone, as web does`() {
        val notice = InactiveAccountNotice.Deactivated(Instant.parse("2026-10-15T01:00:00Z"))
        val now = Instant.parse("2026-10-01T22:00:00Z")

        assertEquals(14L, notice.daysUntilFilesDeleted(now, ZoneOffset.UTC))
        // 2026-10-02 01:00 to 2026-10-15 04:00 at +03:00.
        assertEquals(13L, notice.daysUntilFilesDeleted(now, ZoneOffset.ofHours(3)))
        assertEquals(0L, notice.daysUntilFilesDeleted(Instant.parse("2026-10-15T23:00:00Z"), ZoneOffset.UTC))
        assertEquals(2L, notice.daysUntilFilesDeleted(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC))
        assertNull(InactiveAccountNotice.Deactivated(null).daysUntilFilesDeleted(now, ZoneOffset.UTC))
    }

    private fun account(
        status: String,
        deletesAt: String? = null,
        subAccount: Boolean = false,
    ) = AccountInfo(
        userId = 1,
        username = "user",
        mail = "user@example.com",
        avatarUrl = "",
        disk = AccountDisk(available = 1, size = 2, used = 1),
        settings = AccountSettings(sortBy = "NAME_ASC"),
        accountStatus = status,
        accountActive = status == "active",
        filesWillBeDeletedAt = deletesAt,
        isSubAccount = subAccount,
    )
}
