package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserProfileFieldCoverageTest {

    @Test
    fun `reads every public field the pinned SDK exposes for the profile`() {
        val exposed = DataTypes.USER_PROFILE.allFields.map { it.name }.toSet()

        assertEquals(exposed, userProfileFields.map { it.name }.toSet())
    }

    /** The API validates the profile by these names, which the mapper passes on as the SDK gives them. */
    @Test
    fun `names the fields the way the api validates them`() {
        assertEquals(
            setOf("date_of_birth", "height", "weight", "gender", "nickname"),
            userProfileFields.map { it.name }.toSet(),
        )
    }
}
