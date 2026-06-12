# Java 知识库简明文档

来源: 本地生成的 Java 主题知识库文件
用途: 适合上传到 RAG 知识库并进行向量化检索

## 1. Java 简介

Java 是一种面向对象的通用编程语言，最初由 Sun Microsystems 推出，后来由 Oracle 维护。Java 的核心设计目标是“一次编写，到处运行”，也就是同一份 Java 程序经过编译后，可以在安装了 JVM 的不同操作系统上运行。

Java 常用于后端服务、企业应用、Android 开发、大数据处理、中间件、金融系统和分布式系统。它的生态成熟，工具链完善，拥有大量框架、类库和工程实践。

## 2. Java 的主要特点

Java 的主要特点包括面向对象、跨平台、强类型、自动内存管理、多线程支持和丰富的标准类库。

Java 程序通常先被编译成字节码文件，也就是 `.class` 文件。字节码不是某个具体操作系统的机器码，而是运行在 JVM 上的中间表示。因此，只要目标环境安装了兼容的 JVM，Java 程序就可以运行。

Java 使用垃圾回收机制自动管理对象内存。开发者不需要像 C 或 C++ 那样手动释放大多数对象内存，但仍然需要注意对象引用、资源关闭和内存泄漏问题。

## 3. JDK、JRE 与 JVM

JDK 是 Java Development Kit，即 Java 开发工具包。JDK 包含编译器、运行环境、标准类库和开发工具。开发 Java 程序通常需要安装 JDK。

JRE 是 Java Runtime Environment，即 Java 运行环境。JRE 包含 JVM 和运行 Java 程序所需的基础类库。只运行 Java 程序时，理论上只需要 JRE。

JVM 是 Java Virtual Machine，即 Java 虚拟机。JVM 负责加载字节码、解释或即时编译字节码、管理内存、执行垃圾回收，并提供跨平台运行能力。

## 4. Java 程序的基本结构

一个简单的 Java 程序通常由类、方法和语句组成。`public static void main(String[] args)` 是 Java 命令行程序常见的入口方法。

示例:

```java
public class HelloJava {
    public static void main(String[] args) {
        System.out.println("Hello, Java");
    }
}
```

在 Java 中，类是组织代码的基本单位。对象是类的实例。方法用于描述对象或类可以执行的行为。

## 5. 面向对象编程

Java 是典型的面向对象语言。面向对象编程的核心概念包括封装、继承和多态。

封装是指把数据和操作数据的方法组织在类中，并通过访问控制限制外部直接访问内部状态。Java 中常用 `private` 字段配合 `public` 方法实现封装。

继承允许一个类复用另一个类的属性和方法。Java 使用 `extends` 关键字表示类继承。Java 类只支持单继承，但可以实现多个接口。

多态是指同一个方法调用在运行时可以表现出不同的行为。Java 中常见的多态来自方法重写、父类引用指向子类对象、接口引用指向实现类对象。

## 6. 接口与抽象类

接口用于定义一组能力或契约。类通过 `implements` 关键字实现接口。接口适合表达“能做什么”，例如 `Runnable` 表示可以被执行的任务。

抽象类使用 `abstract` 关键字定义。抽象类可以包含抽象方法，也可以包含普通方法和成员变量。抽象类适合表达一组类的共同基础行为。

接口和抽象类都可以用于抽象设计。一般来说，如果强调能力契约，优先使用接口；如果强调共享状态和通用实现，可以考虑抽象类。

## 7. 常用数据类型

Java 的类型分为基本类型和引用类型。

基本类型包括 `byte`、`short`、`int`、`long`、`float`、`double`、`char` 和 `boolean`。基本类型变量直接保存值。

引用类型包括类、接口、数组、枚举等。引用类型变量保存的是对象引用，而不是对象本身。

`String` 是 Java 中非常常用的引用类型，表示不可变字符串。字符串拼接频繁时，可以使用 `StringBuilder` 来减少中间对象。

## 8. 集合框架

Java 集合框架提供了常用的数据结构接口和实现。常见接口包括 `List`、`Set`、`Map` 和 `Queue`。

`List` 表示有序集合，允许重复元素。常见实现包括 `ArrayList` 和 `LinkedList`。`ArrayList` 查询效率较好，适合随机访问；`LinkedList` 基于链表，在特定插入删除场景下有优势。

`Set` 表示不重复元素集合。常见实现包括 `HashSet`、`LinkedHashSet` 和 `TreeSet`。`HashSet` 基于哈希表，不保证顺序；`TreeSet` 通常按自然顺序或比较器排序。

`Map` 表示键值对集合。常见实现包括 `HashMap`、`LinkedHashMap`、`TreeMap` 和 `ConcurrentHashMap`。`HashMap` 是最常用的非线程安全 Map 实现，`ConcurrentHashMap` 适合并发场景。

## 9. 异常处理

Java 使用异常机制处理程序运行中的错误或异常情况。异常体系的根类型是 `Throwable`，常见子类包括 `Error` 和 `Exception`。

`Error` 通常表示严重错误，例如虚拟机错误或内存溢出，应用程序一般不主动捕获。

`Exception` 表示程序可以处理的异常。`RuntimeException` 及其子类属于运行时异常，常见例子包括空指针异常、数组越界异常和非法参数异常。

Java 使用 `try-catch-finally` 处理异常。对于需要关闭的资源，推荐使用 `try-with-resources`，它可以自动关闭实现了 `AutoCloseable` 的资源。

## 10. 泛型

泛型允许在类、接口和方法中使用类型参数。泛型可以提高类型安全，减少强制类型转换。

例如，`List<String>` 表示列表中的元素类型是字符串。编译器会阻止向这个列表中加入非字符串对象。

Java 泛型在运行时存在类型擦除。类型擦除意味着大部分泛型类型信息在运行时不可直接获取，因此泛型主要在编译期提供类型检查能力。

## 11. Java 并发基础

Java 内置了多线程支持。创建线程可以继承 `Thread` 类，也可以实现 `Runnable` 或 `Callable` 接口。现代 Java 开发中更常使用线程池来管理线程。

线程池可以复用线程，减少频繁创建和销毁线程的成本。`ExecutorService` 是常见的线程池接口。

并发编程需要关注线程安全、共享变量可见性、原子性和锁竞争。常用工具包括 `synchronized`、`Lock`、`volatile`、原子类、阻塞队列和并发集合。

`volatile` 可以保证变量修改对其他线程可见，但不能保证复合操作的原子性。对于计数器这类场景，可以使用 `AtomicInteger` 等原子类。

## 12. JVM 内存区域

JVM 运行时内存通常包括堆、虚拟机栈、本地方法栈、方法区和程序计数器。

堆主要存放对象实例，是垃圾回收器重点管理的区域。大多数对象都在堆上分配。

虚拟机栈用于存储方法调用相关的信息，例如局部变量表、操作数栈、动态链接和方法返回地址。每个线程都有自己的虚拟机栈。

方法区用于存放类元数据、常量、静态变量等信息。在较新的 HotSpot JVM 中，元空间用于承载很多类元数据。

## 13. 垃圾回收

Java 的垃圾回收机制负责回收不再被引用的对象。常见判断对象是否可回收的方法是可达性分析。

如果一个对象无法从 GC Roots 通过引用链访问到，那么它通常被认为是不可达对象，可以被垃圾回收。

常见垃圾回收器包括 Serial、Parallel、CMS、G1、ZGC 和 Shenandoah。不同垃圾回收器在吞吐量、停顿时间和内存占用方面有不同取舍。

在服务端应用中，G1 是较常见的选择。对低延迟要求更高的应用，可能会考虑 ZGC 或 Shenandoah。

## 14. Java I/O 与 NIO

Java I/O 提供了面向流的输入输出能力，常见类包括 `InputStream`、`OutputStream`、`Reader` 和 `Writer`。

字节流适合处理二进制数据，字符流适合处理文本数据。处理文本时需要注意字符编码，例如 UTF-8。

Java NIO 提供了缓冲区、通道和选择器等能力，适合构建高性能网络程序。NIO 的核心概念包括 `Buffer`、`Channel` 和 `Selector`。

## 15. Lambda 与 Stream

Java 8 引入了 Lambda 表达式和 Stream API。Lambda 表达式可以让函数式接口的实现更简洁。

Stream API 用于以声明式方式处理集合数据。常见操作包括 `map`、`filter`、`sorted`、`distinct`、`collect` 和 `reduce`。

Stream 不会修改原集合，而是通过流水线生成处理结果。对于简单集合处理，Stream 可以提升代码可读性；对于复杂性能敏感场景，需要注意装箱、拆箱和并行流开销。

## 16. Maven 与 Gradle

Maven 和 Gradle 是 Java 项目常用构建工具。它们负责依赖管理、编译、测试、打包和发布。

Maven 使用 `pom.xml` 描述项目结构和依赖。Maven 约定优于配置，项目目录结构相对固定。

Gradle 使用 Groovy 或 Kotlin DSL 描述构建逻辑，灵活性更强。大型项目中，Gradle 常用于复杂构建和多模块管理。

## 17. Spring 与 Spring Boot

Spring 是 Java 生态中非常重要的企业级应用框架。它的核心能力包括依赖注入、面向切面编程、事务管理、数据访问和 Web 开发支持。

Spring Boot 是基于 Spring 的快速开发框架。它通过自动配置、起步依赖和内嵌服务器简化应用搭建。

在 Spring Boot 中，常见注解包括 `@SpringBootApplication`、`@RestController`、`@Service`、`@Repository`、`@Component`、`@Autowired`、`@Bean` 和 `@Configuration`。

Spring Boot 常用于构建 REST API、微服务、后台管理系统和企业业务服务。

## 18. Java Web 开发

Java Web 开发常见技术包括 Servlet、Spring MVC、Spring Boot、REST API、JSON 序列化、数据库访问和安全认证。

在 Spring MVC 中，控制器负责接收 HTTP 请求并返回响应。`@GetMapping`、`@PostMapping`、`@PutMapping` 和 `@DeleteMapping` 常用于声明接口路径和请求方法。

REST API 通常使用 JSON 作为数据交换格式。Java 中常用 Jackson 将对象序列化为 JSON，或将 JSON 反序列化为对象。

## 19. 数据库访问

Java 访问数据库的基础规范是 JDBC。JDBC 提供连接数据库、执行 SQL 和读取结果集的能力。

在实际项目中，常用 MyBatis、JPA 或 Spring Data JPA 简化数据库访问。MyBatis 更接近 SQL，适合复杂查询控制；JPA 更强调对象关系映射。

数据库访问需要注意连接池、事务、索引、慢查询、SQL 注入和数据一致性问题。

## 20. Java 项目常见分层

典型 Java 后端项目会分为 Controller、Service、Repository 或 Mapper、Domain 或 Entity、DTO、Config 等层。

Controller 层处理 HTTP 请求和响应。Service 层承载业务逻辑。Repository 或 Mapper 层负责数据访问。DTO 用于接口输入输出数据传输。

清晰分层可以降低模块耦合，让代码更容易测试和维护。

## 21. 常见 Java 编码实践

变量和方法命名应清晰表达意图。类名通常使用大驼峰命名，方法名和变量名通常使用小驼峰命名。

业务代码应避免过长方法。复杂逻辑可以拆分为多个私有方法或独立服务。

集合和对象使用前应考虑空值情况。对于外部输入，要进行必要校验。

日志应记录关键业务事件和异常上下文，但不应记录敏感信息，例如密码、密钥和完整身份证号。

## 22. Java 常见面试问题简答

问题: Java 为什么可以跨平台？
答案: Java 源代码编译为字节码，字节码运行在 JVM 上。不同平台有对应的 JVM，因此同一份字节码可以在多个平台运行。

问题: `ArrayList` 和 `LinkedList` 有什么区别？
答案: `ArrayList` 基于动态数组，随机访问效率高；`LinkedList` 基于链表，节点插入删除在某些场景下更灵活，但随机访问较慢。

问题: `HashMap` 是否线程安全？
答案: `HashMap` 不是线程安全集合。并发场景可以使用 `ConcurrentHashMap`，或在外部进行同步控制。

问题: `==` 和 `equals` 有什么区别？
答案: 对基本类型，`==` 比较值。对引用类型，`==` 比较引用地址。`equals` 通常用于比较对象逻辑相等性，但具体行为取决于类是否重写该方法。

问题: 什么是 Spring 的依赖注入？
答案: 依赖注入是由容器负责创建对象并注入依赖对象的机制。它可以降低代码耦合，提高可测试性和可维护性。

## 23. 检索建议

如果要查询 Java 基础，可以搜索“Java 特点”“JDK JRE JVM”“面向对象”“集合框架”。

如果要查询性能和运行机制，可以搜索“JVM 内存区域”“垃圾回收”“线程池”“并发工具”。

如果要查询 Java 后端开发，可以搜索“Spring Boot”“REST API”“数据库访问”“项目分层”。

如果要查询面试问题，可以搜索“HashMap 线程安全”“ArrayList LinkedList 区别”“== equals 区别”“依赖注入”。
