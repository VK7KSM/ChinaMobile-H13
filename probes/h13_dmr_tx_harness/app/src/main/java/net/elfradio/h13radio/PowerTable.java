package net.elfradio.h13radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 功率标定表：功率寄存器码值与实际瓦数的对应。
 *
 * <p>**标定点不足时拒绝给值，不外推。** 这不是保守，是必须的：
 * 2026-09-19 同一配置下五次发射实测 0.05 / 0.279 / 0.803 / 1.24 瓦，
 * 相差二十五倍（{@code McuAssets.CHANNEL_POWER_CODE} 的注释记了这件事）。
 * 在这种离散度上做外推，等于凭空编造发射功率。
 *
 * <p>目前只有一个标定点，因此**任何求解都会被拒绝**——这是正确行为，
 * 不是缺陷。要能用，得由操作者带功率表做多点标定。
 */
public final class PowerTable {
    /** 操作安全上限，超出一律拒绝。 */
    public static final double MAX_WATTS = 5.0;

    /** 一个标定点：码值、瓦数、出处。 */
    public static final class Point {
        public final int code;
        public final double watts;
        public final String source;

        public Point(int code, double watts, String source) {
            this.code = code;
            this.watts = watts;
            this.source = source;
        }
    }

    /** 结果：要么有值，要么有拒绝的理由，不会两者皆空。 */
    public static final class Result {
        public final boolean ok;
        public final double watts;
        public final int code;
        public final String why;

        private Result(boolean ok, double watts, int code, String why) {
            this.ok = ok;
            this.watts = watts;
            this.code = code;
            this.why = why;
        }

        public static Result refuse(String why) {
            return new Result(false, 0, 0, why);
        }

        static Result value(double watts, int code, String why) {
            return new Result(true, watts, code, why);
        }
    }

    private final List<Point> points = new ArrayList<>();

    public PowerTable() {
        // 与 tools/offline/power_calibration.py 的 POINTS 保持一致。
        points.add(new Point(2030, 1.15,
                "2026-09-20 第二十次发射，操作者功率表读数"));
        points.sort((a, b) -> Integer.compare(a.code, b.code));
    }

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    /** 加一个标定点。多点之后插值才会被放行。 */
    public void addPoint(int code, double watts, String source) {
        points.add(new Point(code, watts, source));
        points.sort((a, b) -> Integer.compare(a.code, b.code));
    }

    public Result wattsForCode(int code) {
        if (points.isEmpty()) {
            return Result.refuse("标定表为空");
        }
        for (Point p : points) {
            if (p.code == code) {
                return Result.value(p.watts, code, "标定点");
            }
        }
        if (points.size() < 2) {
            return Result.refuse("标定点不足两个，无法插值；外推不安全，拒绝给值");
        }
        Point lo = points.get(0);
        Point hi = points.get(points.size() - 1);
        if (code < lo.code || code > hi.code) {
            return Result.refuse(String.format(Locale.US,
                    "超出标定范围 [%d, %d]，外推不安全，拒绝给值", lo.code, hi.code));
        }
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            if (a.code <= code && code <= b.code) {
                double t = (code - a.code) / (double) (b.code - a.code);
                return Result.value(a.watts + t * (b.watts - a.watts), code,
                        String.format(Locale.US, "在 %d 与 %d 之间线性插值",
                                a.code, b.code));
            }
        }
        return Result.refuse("未命中区间");
    }

    public Result codeForWatts(double watts) {
        if (watts <= 0) {
            return Result.refuse("功率须为正");
        }
        if (watts > MAX_WATTS) {
            return Result.refuse(String.format(Locale.US,
                    "超过安全上限 %.1f 瓦，拒绝", MAX_WATTS));
        }
        if (points.size() < 2) {
            return Result.refuse("标定点不足两个，无法求解；外推不安全，拒绝给值");
        }
        double lo = points.get(0).watts;
        double hi = points.get(points.size() - 1).watts;
        if (watts < lo || watts > hi) {
            return Result.refuse(String.format(Locale.US,
                    "超出标定范围 [%.2f, %.2f] 瓦，拒绝", lo, hi));
        }
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            if (a.watts <= watts && watts <= b.watts && b.watts != a.watts) {
                double t = (watts - a.watts) / (b.watts - a.watts);
                int code = (int) Math.round(a.code + t * (b.code - a.code));
                return Result.value(watts, code, String.format(Locale.US,
                        "在 %.2f 与 %.2f 瓦之间插值", a.watts, b.watts));
            }
        }
        return Result.refuse("未命中区间");
    }
}
