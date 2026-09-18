.class public Lcom/bozhou/interphone/util/FormatUtils;
.super Ljava/lang/Object;
.source "FormatUtils.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 11
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static dateFormat(Landroid/content/Context;J)Ljava/lang/CharSequence;
    .locals 1

    .line 39
    invoke-static {}, Ljava/util/Calendar;->getInstance()Ljava/util/Calendar;

    move-result-object v0

    .line 40
    invoke-virtual {v0, p1, p2}, Ljava/util/Calendar;->setTimeInMillis(J)V

    const p1, 0x7f100087

    .line 41
    invoke-virtual {p0, p1}, Landroid/content/Context;->getString(I)Ljava/lang/String;

    move-result-object p0

    invoke-static {p0, v0}, Landroid/text/format/DateFormat;->format(Ljava/lang/CharSequence;Ljava/util/Calendar;)Ljava/lang/CharSequence;

    move-result-object p0

    return-object p0
.end method

.method public static durationFormat(Landroid/content/Context;I)Ljava/lang/String;
    .locals 10

    add-int/lit16 p1, p1, 0x3e7

    .line 13
    div-int/lit16 p1, p1, 0x3e8

    const/16 p0, 0xe10

    const-wide/16 v0, 0x0

    if-lt p1, p0, :cond_0

    .line 18
    div-int/lit16 p0, p1, 0xe10

    int-to-long v2, p0

    int-to-long p0, p1

    const-wide/16 v4, 0xe10

    mul-long v4, v4, v2

    sub-long/2addr p0, v4

    long-to-int p1, p0

    goto :goto_0

    :cond_0
    move-wide v2, v0

    :goto_0
    const/16 p0, 0x3c

    if-lt p1, p0, :cond_1

    .line 22
    div-int/lit8 p0, p1, 0x3c

    int-to-long v4, p0

    int-to-long p0, p1

    const-wide/16 v6, 0x3c

    mul-long v6, v6, v4

    sub-long/2addr p0, v6

    long-to-int p1, p0

    goto :goto_1

    :cond_1
    move-wide v4, v0

    :goto_1
    int-to-long p0, p1

    const/4 v6, 0x2

    const/4 v7, 0x1

    const/4 v8, 0x0

    cmp-long v9, v2, v0

    if-lez v9, :cond_2

    .line 28
    new-instance v0, Ljava/util/Formatter;

    invoke-direct {v0}, Ljava/util/Formatter;-><init>()V

    const/4 v1, 0x3

    new-array v1, v1, [Ljava/lang/Object;

    invoke-static {v2, v3}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object v2

    aput-object v2, v1, v8

    invoke-static {v4, v5}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object v2

    aput-object v2, v1, v7

    invoke-static {p0, p1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object p0

    aput-object p0, v1, v6

    const-string p0, "%1$dh%2$d\u2032%3$d\u2033"

    invoke-virtual {v0, p0, v1}, Ljava/util/Formatter;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/Formatter;

    move-result-object p0

    invoke-virtual {p0}, Ljava/util/Formatter;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0

    :cond_2
    cmp-long v2, v4, v0

    if-lez v2, :cond_3

    .line 31
    new-instance v0, Ljava/util/Formatter;

    invoke-direct {v0}, Ljava/util/Formatter;-><init>()V

    new-array v1, v6, [Ljava/lang/Object;

    invoke-static {v4, v5}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object v2

    aput-object v2, v1, v8

    invoke-static {p0, p1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object p0

    aput-object p0, v1, v7

    const-string p0, "%1$d\u2032%2$d\u2033"

    invoke-virtual {v0, p0, v1}, Ljava/util/Formatter;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/Formatter;

    move-result-object p0

    invoke-virtual {p0}, Ljava/util/Formatter;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0

    .line 34
    :cond_3
    new-instance v0, Ljava/util/Formatter;

    invoke-direct {v0}, Ljava/util/Formatter;-><init>()V

    new-array v1, v7, [Ljava/lang/Object;

    invoke-static {p0, p1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object p0

    aput-object p0, v1, v8

    const-string p0, "%1$d\u2033"

    invoke-virtual {v0, p0, v1}, Ljava/util/Formatter;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/Formatter;

    move-result-object p0

    invoke-virtual {p0}, Ljava/util/Formatter;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static messageDateFormat(J)Ljava/lang/CharSequence;
    .locals 1

    .line 45
    invoke-static {}, Ljava/util/Calendar;->getInstance()Ljava/util/Calendar;

    move-result-object v0

    .line 46
    invoke-virtual {v0, p0, p1}, Ljava/util/Calendar;->setTimeInMillis(J)V

    const-string p0, "HH:mm:ss"

    .line 47
    invoke-static {p0, v0}, Landroid/text/format/DateFormat;->format(Ljava/lang/CharSequence;Ljava/util/Calendar;)Ljava/lang/CharSequence;

    move-result-object p0

    return-object p0
.end method
