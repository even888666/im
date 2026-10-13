package cn.android666.im.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [RootCaTrust.fromResources] 能从构建时打包的 res/raw/im_root_ca 加载根证书 */
@RunWith(RobolectricTestRunner::class)
class RootCaTrustResourceTest {

    @Test
    fun fromResources_loadsPackagedRootCa() {
        val trust = RootCaTrust.fromResources(ApplicationProvider.getApplicationContext<Context>())

        assertThat(trust.certificates).isNotEmpty()
        // 打包的是 CA 证书（basicConstraints CA:TRUE 时 getBasicConstraints() >= 0）
        assertThat(trust.certificates.first().basicConstraints).isAtLeast(0)
    }
}
