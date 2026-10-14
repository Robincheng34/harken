// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角 Token（iOS `Theme.Radius` 1:1 移植）：
 * badge 4 / small 6 / standard 8 / large 12 / panel 20。
 */
val KumoneShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
