package test

fun <T> outer(arg: T): T {
    class localClass(val v: T) {
        fun member() = v
    }

    fun innerFun() = localClass(arg).member()

    return innerFun()
}