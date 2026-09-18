.class public Lcom/bozhou/interphone/util/SystemPropertiesUtils;
.super Ljava/lang/Object;
.source "SystemPropertiesUtils.java"


# static fields
.field private static final TAG:Ljava/lang/String; = "SystemPropertiesUtils"

.field private static mClassType:Ljava/lang/Class;
    .annotation system Ldalvik/annotation/Signature;
        value = {
            "Ljava/lang/Class<",
            "*>;"
        }
    .end annotation
.end field

.field private static mGetMethod:Ljava/lang/reflect/Method;

.field private static mSetMethod:Ljava/lang/reflect/Method;


# direct methods
.method static constructor <clinit>()V
    .locals 0

    return-void
.end method

.method public constructor <init>()V
    .locals 0

    .line 14
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .locals 4

    .line 48
    :try_start_0
    invoke-static {}, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->getMethod()Ljava/lang/reflect/Method;

    move-result-object v0

    const/4 v1, 0x0

    const/4 v2, 0x1

    new-array v2, v2, [Ljava/lang/Object;

    const/4 v3, 0x0

    aput-object p0, v2, v3

    invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object p0

    check-cast p0, Ljava/lang/String;

    .line 49
    invoke-static {p0}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    move-result v0
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    if-nez v0, :cond_0

    return-object p0

    :catch_0
    const-string p0, "SystemPropertiesUtils"

    const-string v0, "Unable to read system properties"

    .line 53
    invoke-static {p0, v0}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I

    :cond_0
    return-object p1
.end method

.method private static getMethod()Ljava/lang/reflect/Method;
    .locals 4
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 31
    sget-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mGetMethod:Ljava/lang/reflect/Method;

    if-nez v0, :cond_0

    .line 32
    invoke-static {}, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->getSystemPropertiesClass()Ljava/lang/Class;

    move-result-object v0

    const/4 v1, 0x1

    new-array v1, v1, [Ljava/lang/Class;

    const/4 v2, 0x0

    .line 33
    const-class v3, Ljava/lang/String;

    aput-object v3, v1, v2

    const-string v2, "get"

    invoke-virtual {v0, v2, v1}, Ljava/lang/Class;->getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;

    move-result-object v0

    sput-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mGetMethod:Ljava/lang/reflect/Method;

    .line 35
    :cond_0
    sget-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mGetMethod:Ljava/lang/reflect/Method;

    return-object v0
.end method

.method private static getSystemPropertiesClass()Ljava/lang/Class;
    .locals 1
    .annotation system Ldalvik/annotation/Signature;
        value = {
            "()",
            "Ljava/lang/Class<",
            "*>;"
        }
    .end annotation

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/ClassNotFoundException;
        }
    .end annotation

    .line 24
    sget-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mClassType:Ljava/lang/Class;

    if-nez v0, :cond_0

    const-string v0, "android.os.SystemProperties"

    .line 25
    invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;

    move-result-object v0

    sput-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mClassType:Ljava/lang/Class;

    .line 27
    :cond_0
    sget-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mClassType:Ljava/lang/Class;

    return-object v0
.end method

.method public static set(Ljava/lang/String;Ljava/lang/String;)V
    .locals 4

    .line 61
    :try_start_0
    invoke-static {}, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->setMethod()Ljava/lang/reflect/Method;

    move-result-object v0

    const/4 v1, 0x0

    const/4 v2, 0x2

    new-array v2, v2, [Ljava/lang/Object;

    const/4 v3, 0x0

    aput-object p0, v2, v3

    const/4 p0, 0x1

    aput-object p1, v2, p0

    invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_0

    :catch_0
    const-string p0, "SystemPropertiesUtils"

    const-string p1, "Unable to set system properties"

    .line 63
    invoke-static {p0, p1}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I

    :goto_0
    return-void
.end method

.method private static setMethod()Ljava/lang/reflect/Method;
    .locals 4
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 39
    sget-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mSetMethod:Ljava/lang/reflect/Method;

    if-nez v0, :cond_0

    .line 40
    invoke-static {}, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->getSystemPropertiesClass()Ljava/lang/Class;

    move-result-object v0

    const/4 v1, 0x2

    new-array v1, v1, [Ljava/lang/Class;

    const/4 v2, 0x0

    .line 41
    const-class v3, Ljava/lang/String;

    aput-object v3, v1, v2

    const/4 v2, 0x1

    const-class v3, Ljava/lang/String;

    aput-object v3, v1, v2

    const-string v2, "set"

    invoke-virtual {v0, v2, v1}, Ljava/lang/Class;->getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;

    move-result-object v0

    sput-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mSetMethod:Ljava/lang/reflect/Method;

    .line 43
    :cond_0
    sget-object v0, Lcom/bozhou/interphone/util/SystemPropertiesUtils;->mSetMethod:Ljava/lang/reflect/Method;

    return-object v0
.end method
