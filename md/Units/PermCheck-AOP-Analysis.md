# Diana Knowledge Base - PermCheck 权限检查管理体系详解

## 📋 目录
1. [系统概述](#系统概述)
2. [注解定义](#注解定义)
3. [AOP 切片实现](#aop-切片实现)
4. [权限检查服务](#权限检查服务)
5. [权限规则](#权限规则)
6. [使用示例](#使用示例)
7. [工作流程](#工作流程)
8. [架构设计](#架构设计)

---

## 系统概述

### 目的
PermCheck 是 Diana Knowledge Base 项目中实现**声明式权限检查**的完整解决方案。通过注解 + AOP 切片的方式，在方法执行前进行权限验证，提供统一的权限控制机制。

### 核心特性
✅ **声明式权限检查** - 通过注解直接标记需要权限验证的方法  
✅ **灵活的权限维度** - 支持按 Topic、Knowledge 多维度检查  
✅ **SpEL 表达式支持** - 动态获取方法参数作为权限检查条件  
✅ **多规则支持** - OWNER_OR_PROJECT_ADMIN 和 PROJECT_MEMBER 两种规则  
✅ **单一职责** - 权限检查逻辑集中在一处，易于维护和扩展

### 技术栈
- **AOP 框架**：Spring AOP (AspectJ)
- **表达式解析**：Spring Expression Language (SpEL)
- **认证上下文**：DataSuite Auth ThreadLocal
- **外部接口**：RAM API (权限管理)

---

## 注解定义

### 文件位置
```
diana-knowledge-base-core/src/main/java/com/shopee/di/diana/kb/aop/PermCheck.java
```

### 注解完整代码

```java
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PermCheck {

    /**
     * 话题ID
     * <p>
     * 用于标识权限检查的话题维度，通常对应知识库中的话题或主题。
     * 支持SpEL表达式，如 "#topicId"。
     * 
     * @return 话题ID，默认为空字符串
     */
    String topicId() default "";

    /**
     * 话题ID列表
     * <p>
     * 用于标识权限检查的多个话题维度，支持SpEL表达式，如 "#topicIds"。
     * 
     * @return 话题ID列表，默认为空数组
     */
    String[] topicIds() default {};

    /**
     * 知识类型
     * <p>
     * 用于标识权限检查的知识类型维度，如文档、表格、数据源等。
     * 
     * @return 知识类型，默认为空字符串
     */
    String knowledgeType() default "";

    /**
     * 知识ID
     * <p>
     * 用于标识权限检查的具体知识资源ID，通常对应具体的文档、表格或数据源。
     * 支持SpEL表达式，如 "#knowledgeId"。
     * 
     * @return 知识ID，默认为空字符串
     */
    String knowledgeId() default "";

    /**
     * 知识ID列表
     * <p>
     * 用于标识权限检查的多个知识资源ID，支持SpEL表达式，如 "#knowledgeIds"。
     * 
     * @return 知识ID列表，默认为空数组
     */
    String[] knowledgeIds() default {};

    /**
     * 权限检查规则
     * <p>
     * 用户确定Permission check 规则，支持OwnerOrProjectAdmin 或 ProjectMember两种规则。
     *
     * @return Permission Check Rule
     */
    PermissionCheckRule checkRule() default PermissionCheckRule.OWNER_OR_PROJECT_ADMIN;
}
```

### 注解属性说明

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `topicId` | String | "" | 单个话题ID，支持 SpEL 表达式（如 "#topicId"） |
| `topicIds` | String[] | {} | 多个话题ID 列表，支持 SpEL 表达式（如 "#topicIds"） |
| `knowledgeType` | String | "" | 知识类型（DOCUMENT/GLOSSARY/RULES），支持 SpEL |
| `knowledgeId` | String | "" | 单个知识资源ID，支持 SpEL 表达式 |
| `knowledgeIds` | String[] | {} | 多个知识资源ID，支持 SpEL 表达式 |
| `checkRule` | PermissionCheckRule | OWNER_OR_PROJECT_ADMIN | 权限检查规则 |

### SpEL 表达式示例

```java
// 示例 1：直接使用方法参数
@PermCheck(topicId = "#topicId")
public void updateTopic(Long topicId) { }

// 示例 2：使用多个 topicIds
@PermCheck(topicIds = "#topicIds")
public void batchUpdateTopics(List<Long> topicIds) { }

// 示例 3：指定权限规则
@PermCheck(topicId = "#topicId", checkRule = PermissionCheckRule.PROJECT_MEMBER)
public void viewTopic(Long topicId) { }

// 示例 4：知识库权限检查
@PermCheck(knowledgeType = "DOCUMENT", knowledgeId = "#documentId")
public void viewDocument(Long documentId) { }

// 示例 5：字面量值（不使用 # 前缀）
@PermCheck(topicId = "123")  // 直接指定 topicId 为 123
public void staticTopic() { }
```

---

## AOP 切片实现

### 文件位置
```
diana-knowledge-base-core/src/main/java/com/shopee/di/diana/kb/aop/PermCheckAspect.java
```

### 切片类完整代码

```java
@Aspect
@Component
@Slf4j
public class PermCheckAspect {

  @Autowired
  private PermissionCheckService permissionCheckService;

  /**
   * @param joinPoint 连接点
   * @param permCheck 权限检查注解
   * @return 方法执行结果
   * @throws Throwable 权限检查失败或方法执行异常
   */
  @Around("@annotation(permCheck)")
  public Object checkPermission(ProceedingJoinPoint joinPoint, PermCheck permCheck)
      throws Throwable {
    log.debug("Starting permission check for method: {} under rule: {}",
        joinPoint.getSignature().getName(), permCheck.checkRule().name());

    String currentUserEmail = DataSuiteAuthThreadLocal.getEmail();

    // 如果用户既不是项目管理员也不是所有者，则抛出异常
    if (!checkUserPermissionUnderRule(joinPoint, currentUserEmail, permCheck)) {
      throw new SecurityException(
          String.format("user %s permission check failed under rule: %s ",
              currentUserEmail, permCheck.checkRule().name()));
    }

    // 权限检查通过，继续执行原方法
    return joinPoint.proceed();
  }

  private boolean checkUserPermissionUnderRule(ProceedingJoinPoint joinPoint,
      String currentUserEmail,
      PermCheck permCheck) {
    PermissionCheckRule checkRule = permCheck.checkRule();
    
    // 1. 检查单个 topicId
    if (StringUtils.isNotEmpty(permCheck.topicId())) {
      String topicId = resolveFromAnnotation(joinPoint, permCheck.topicId());
      return permissionCheckService.checkUserPermissionUnderRule(topicId, currentUserEmail,
          checkRule);
    } 
    // 2. 检查多个 topicIds（所有都有权限才返回true）
    else if (permCheck.topicIds().length > 0) {
      String[] topicIds = resolveArrayFromAnnotation(joinPoint, permCheck.topicIds());
      return Arrays.stream(topicIds)
          .allMatch(topicId -> permissionCheckService.checkUserPermissionUnderRule(topicId,
              currentUserEmail, checkRule));
    } 
    // 3. 检查单个知识资源（知识类型+知识ID）
    else if (StringUtils.isNotEmpty(permCheck.knowledgeType()) && StringUtils.isNotEmpty(
        permCheck.knowledgeId())) {
      String knowledgeId = resolveFromAnnotation(joinPoint, permCheck.knowledgeId());
      return permissionCheckService.checkUserPermissionUnderRule(permCheck.knowledgeType(),
          knowledgeId, currentUserEmail,
          checkRule);
    } 
    // 4. 检查多个知识资源
    else if (StringUtils.isNotEmpty(permCheck.knowledgeType())
        && permCheck.knowledgeIds().length > 0) {
      String[] knowledgeIds = resolveArrayFromAnnotation(joinPoint, permCheck.knowledgeIds());
      return Arrays.stream(knowledgeIds).allMatch(
          knowledgeId -> permissionCheckService.checkUserPermissionUnderRule(
              permCheck.knowledgeType(), knowledgeId,
              currentUserEmail, checkRule));
    }

    return false;
  }

  /**
   * 解析注解中的取值，支持 SpEL（例如 "#topicId"）。 
   * 若不是以 '#' 开头，则直接返回原字符串。
   */
  private String resolveFromAnnotation(ProceedingJoinPoint joinPoint, String exprOrLiteral) {
    if (StringUtils.isBlank(exprOrLiteral)) {
      return null;
    }
    // 不是 SpEL 表达式，直接返回字面量
    if (!exprOrLiteral.startsWith("#")) {
      return exprOrLiteral;
    }
    
    // 是 SpEL 表达式，解析获取参数值
    MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
    String[] parameterNames = methodSignature.getParameterNames();
    Object[] args = joinPoint.getArgs();

    EvaluationContext context = new StandardEvaluationContext();
    if (parameterNames != null) {
      // 将方法参数加入评估上下文
      for (int i = 0; i < parameterNames.length; i++) {
        context.setVariable(parameterNames[i], args[i]);
      }
    }

    ExpressionParser parser = new SpelExpressionParser();
    Expression expression = parser.parseExpression(exprOrLiteral);
    Object value = expression.getValue(context);
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 解析注解中的数组取值，支持 SpEL（例如 "#topicIds"）。 
   * 若不是以 '#' 开头，则直接返回原字符串数组。
   */
  private String[] resolveArrayFromAnnotation(ProceedingJoinPoint joinPoint,
      String[] exprOrLiterals) {
    if (exprOrLiterals == null || exprOrLiterals.length == 0) {
      return new String[0];
    }

    List<String> results = new ArrayList<>();
    MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
    String[] parameterNames = methodSignature.getParameterNames();
    Object[] args = joinPoint.getArgs();

    EvaluationContext context = new StandardEvaluationContext();
    if (parameterNames != null) {
      for (int i = 0; i < parameterNames.length; i++) {
        context.setVariable(parameterNames[i], args[i]);
      }
    }

    ExpressionParser parser = new SpelExpressionParser();

    for (String exprOrLiteral : exprOrLiterals) {
      if (StringUtils.isBlank(exprOrLiteral)) {
        continue;
      }

      if (exprOrLiteral.startsWith("#")) {
        // SpEL 表达式处理
        Expression expression = parser.parseExpression(exprOrLiteral);
        Object value = expression.getValue(context);
        if (value == null) {
          continue;
        }
        
        // 处理数组类型
        if (value.getClass().isArray()) {
          int len = java.lang.reflect.Array.getLength(value);
          for (int i = 0; i < len; i++) {
            Object elem = java.lang.reflect.Array.get(value, i);
            if (elem != null) {
              results.add(String.valueOf(elem));
            }
          }
        } 
        // 处理集合类型
        else if (value instanceof Collection) {
          for (Object elem : (Collection<?>) value) {
            if (elem != null) {
              results.add(String.valueOf(elem));
            }
          }
        } 
        // 处理单值类型
        else {
          results.add(String.valueOf(value));
        }
      } else {
        // 字面量处理
        String literal = exprOrLiteral.trim();
        if (literal.startsWith("[") && literal.endsWith("]")) {
          literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.contains(",")) {
          for (String part : literal.split(",")) {
            String p = part.trim();
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
              p = p.substring(1, p.length() - 1);
            }
            if (!p.isEmpty()) {
              results.add(p);
            }
          }
        } else if (!literal.isEmpty()) {
          results.add(literal);
        }
      }
    }

    return results.toArray(new String[0]);
  }
}
```

### AOP 执行流程详解

```
1. 方法调用
   ↓
2. Spring AOP 拦截 (@Around 通知)
   ├─ 获取当前用户邮箱 (DataSuiteAuthThreadLocal)
   ├─ 提取注解属性 (@PermCheck)
   ↓
3. 解析 SpEL 表达式
   ├─ 获取方法参数名和参数值
   ├─ 创建评估上下文 (EvaluationContext)
   ├─ 解析 SpEL 表达式为具体值
   ↓
4. 权限检查
   ├─ 调用 PermissionCheckService
   ├─ 根据规则检查权限
   ↓
5. 判断结果
   ├─ 权限通过 → 执行原方法 (joinPoint.proceed())
   └─ 权限失败 → 抛出 SecurityException
```

### AOP 关键方法

| 方法 | 作用 |
|------|------|
| `checkPermission()` | AOP 主切片方法，使用 @Around 通知 |
| `checkUserPermissionUnderRule()` | 判断检查哪个维度的权限 |
| `resolveFromAnnotation()` | 解析单个 SpEL 表达式或字面量 |
| `resolveArrayFromAnnotation()` | 解析数组 SpEL 表达式或字面量 |

---

## 权限检查服务

### 文件位置
```
diana-knowledge-base-core/src/main/java/com/shopee/di/diana/kb/service/PermissionCheckService.java
```

### 核心方法详解

#### 1. Topic 权限检查

```java
public boolean checkUserPermissionUnderRule(String topicIdStr, String currentUserEmail,
    PermissionCheckRule checkRule) {
  // 1. 根据 topicId 获取 Topic 对象
  ChatbiTopicDao topicDao = chatbiTopicManager.findById(Long.valueOf(topicIdStr)).orElseThrow();
  
  // 2. 调用规则检查
  return doRulePermissionCheck(currentUserEmail, checkRule, topicDao.getOwner(),
      topicDao.getProjectCode());
}
```

#### 2. 知识资源权限检查

```java
public boolean checkUserPermissionUnderRule(String knowledgeTypeStr, String knowledgeIdStr,
    String currentUserEmail, PermissionCheckRule checkRule) {
  KnowledgeType knowledgeType = KnowledgeType.valueOf(knowledgeTypeStr);
  switch (knowledgeType) {
    case DOCUMENT:
      BusinessDocumentDao documentDao =
          businessDocumentManager.findById(Long.valueOf(knowledgeIdStr)).orElseThrow();
      return doRulePermissionCheck(currentUserEmail, checkRule, documentDao.getOwner(),
          documentDao.getProjectCode());
    case GLOSSARY:
      BusinessGlossaryDao glossaryDao =
          businessGlossaryManager.findById(Long.valueOf(knowledgeIdStr)).orElseThrow();
      return doRulePermissionCheck(currentUserEmail, checkRule, glossaryDao.getOwner(),
          glossaryDao.getProjectCode());
    case RULES:
      BusinessRulesDao rulesDao =
          businessRulesManager.findById(Long.valueOf(knowledgeIdStr)).orElseThrow();
      return doRulePermissionCheck(currentUserEmail, checkRule, rulesDao.getOwner(),
          rulesDao.getProjectCode());
    default:
      return false;
  }
}
```

#### 3. 规则检查

```java
public boolean doRulePermissionCheck(String currentUserEmail, PermissionCheckRule checkRule,
    String owner, String projectCode) {
  return switch (checkRule) {
    // OWNER_OR_PROJECT_ADMIN：用户是所有者或项目管理员
    case OWNER_OR_PROJECT_ADMIN ->
        doCheckIsOwnerOrProjectAdmin(currentUserEmail, owner, projectCode);
    // PROJECT_MEMBER：用户是项目成员
    case PROJECT_MEMBER -> isProjectMember(currentUserEmail, projectCode);
  };
}
```

#### 4. 所有者或管理员检查

```java
public boolean doCheckIsOwnerOrProjectAdmin(String currentUserEmail, String owner,
    String projectCode) {
  log.info("Checking admin or owner permission for user: {} on project: {} or owner: {}",
      currentUserEmail, projectCode, owner);
  // 用户邮箱等于所有者 或 用户是项目管理员
  return currentUserEmail.equals(owner) || isProjectAdmin(currentUserEmail, projectCode);
}
```

#### 5. 项目管理员检查

```java
public boolean isProjectAdmin(String userEmail, String projectCode) {
  try {
    // 调用 RAM API 获取项目详情
    RamResponseDTO<RamProjectDetailDTO> response = ramApiClient.getProjectDetail(projectCode);
    RamProjectDetailDTO projectDetail = response.orElseThrow();
    
    List<RamProjectDetailDTO.UserInfo> projectAdmins = projectDetail.getProjectAdmin();
    
    // 检查用户是否在项目管理员列表中
    return projectAdmins.stream()
        .filter(Objects::nonNull)
        .anyMatch(admin -> userEmail.equals(admin.getEmail()));
        
  } catch (Exception e) {
    log.error("Error occurred while checking project admin permission", e);
    return false;
  }
}
```

#### 6. 项目成员检查

```java
public boolean isProjectMember(String userEmail, String projectCode) {
  try {
    // 调用 RAM API 获取项目详情
    RamResponseDTO<RamProjectDetailDTO> response = ramApiClient.getProjectDetail(projectCode);
    RamProjectDetailDTO projectDetail = response.orElseThrow();
    
    List<RamProjectDetailDTO.UserInfo> projectMembers = projectDetail.getProjectMember();
    
    // 检查用户是否在项目成员列表中
    return projectMembers.stream()
        .filter(Objects::nonNull)
        .anyMatch(member -> userEmail.equals(member.getEmail()));
        
  } catch (Exception e) {
    log.error("Error occurred while checking project member permission", e);
    return false;
  }
}
```

### 依赖组件

| 组件 | 作用 |
|------|------|
| `RamApiClient` | 调用 RAM（权限管理）API 获取项目和用户信息 |
| `ChatbiTopicManager` | 管理 Topic（话题）数据库操作 |
| `BusinessDocumentManager` | 管理文档数据库操作 |
| `BusinessGlossaryManager` | 管理词汇表数据库操作 |
| `BusinessRulesManager` | 管理业务规则数据库操作 |

---

## 权限规则

### 文件位置
```
diana-knowledge-base-core/src/main/java/com/shopee/di/diana/kb/enums/PermissionCheckRule.java
```

### 规则定义

```java
public enum PermissionCheckRule {
  // 所有者或项目管理员
  OWNER_OR_PROJECT_ADMIN,
  // 项目成员
  PROJECT_MEMBER,
  ;
}
```

### 规则说明

| 规则 | 条件 | 使用场景 |
|------|------|---------|
| **OWNER_OR_PROJECT_ADMIN** | 用户是资源所有者 **或** 是项目管理员 | 编辑、删除、配置等高权限操作 |
| **PROJECT_MEMBER** | 用户是项目成员 | 查看、浏览等低权限操作 |

### 权限层级关系

```
项目管理员 (高)
  ↓
项目成员 (中)
  ↓
非成员 (无)
```

---

## 使用示例

### 示例 1：单个 Topic 权限检查

```java
@PostMapping("/topics/{id}")
@PermCheck(topicId = "#id", checkRule = PermissionCheckRule.OWNER_OR_PROJECT_ADMIN)
public ResponseEntity<Void> updateTopic(@PathVariable Long id, @RequestBody TopicDTO dto) {
  // 只有 Topic 所有者或项目管理员才能执行此方法
  topicService.update(id, dto);
  return ResponseEntity.ok().build();
}
```

**执行过程**：
1. 用户发送请求更新 Topic
2. AOP 拦截请求，提取 `id` 参数
3. 调用权限检查，验证用户是否为 Topic 所有者或项目管理员
4. 权限通过 → 执行更新逻辑
5. 权限失败 → 抛出 SecurityException

### 示例 2：多个 Topic 权限检查

```java
@PostMapping("/topics/batch")
@PermCheck(topicIds = "#topicIds", checkRule = PermissionCheckRule.PROJECT_MEMBER)
public ResponseEntity<Void> batchViewTopics(@RequestBody List<Long> topicIds) {
  // 用户必须对所有 Topic 都有权限
  topicService.viewTopics(topicIds);
  return ResponseEntity.ok().build();
}
```

**执行过程**：
1. 用户发送请求查看多个 Topic
2. AOP 拦截请求，提取 `topicIds` 参数
3. 逐一检查每个 Topic，用户必须是该 Topic 所属项目的成员
4. **所有 Topic 都有权限** → 执行查看逻辑
5. **任意 Topic 无权限** → 抛出 SecurityException

### 示例 3：知识资源权限检查

```java
@GetMapping("/documents/{id}")
@PermCheck(knowledgeType = "DOCUMENT", knowledgeId = "#id")
public ResponseEntity<DocumentDTO> getDocument(@PathVariable Long id) {
  // 用户必须是该文档所属项目的成员或文档所有者
  return ResponseEntity.ok(documentService.getById(id));
}
```

### 示例 4：项目实际使用

**FeedbackController.java**：
```java
@PostMapping("/topicId/{topicId}")
@PermCheck(topicId = "#topicId", checkRule = PermissionCheckRule.PROJECT_MEMBER)
public ResponseEntity<Page<FeedbackDTO>> queryFeedback(
    @PathVariable Long topicId,
    @RequestParam int page,
    @RequestParam int size) {
  return ResponseEntity.ok(feedbackService.query(topicId, page, size));
}
```

**TopicPermissionController.java**：
```java
@GetMapping("/details/{topicId}")
@PermCheck(topicId = "#topicId")
public ResponseEntity<TopicDetailsDTO> getTopicDetails(@PathVariable Long topicId) {
  return ResponseEntity.ok(topicService.getDetails(topicId));
}
```

---

## 工作流程

### 完整的权限检查流程

```
1. 用户请求
   POST /topics/1
   Header: Authorization: Bearer <token>
   ↓

2. Spring 控制器映射
   @PostMapping("/topics/{id}")
   @PermCheck(topicId = "#id", ...)
   ↓

3. Spring AOP 拦截
   @Around("@annotation(permCheck)")
   ↓

4. 获取用户信息
   String currentUserEmail = DataSuiteAuthThreadLocal.getEmail()
   // 从认证上下文获取当前用户邮箱
   ↓

5. 解析 SpEL 表达式
   String topicId = resolveFromAnnotation(joinPoint, "#id")
   // 提取方法参数 id 的值
   ↓

6. 调用权限检查服务
   checkUserPermissionUnderRule(topicId, currentUserEmail, checkRule)
   ↓

7a. 权限检查（案例流程）
   ├─ 获取 Topic 对象：ChatbiTopicDao topic = findById(topicId)
   ├─ 获取项目代码：String projectCode = topic.getProjectCode()
   ├─ 检查规则（OWNER_OR_PROJECT_ADMIN）
   │  ├─ 是否所有者：currentUserEmail.equals(topic.getOwner())
   │  │  ├─ 是 → 返回 true ✓
   │  │  └─ 否 → 继续检查
   │  └─ 是否项目管理员：isProjectAdmin(currentUserEmail, projectCode)
   │     ├─ 调用 RAM API 获取项目管理员列表
   │     ├─ 检查用户是否在列表中
   │     └─ 返回 true/false
   ↓

8. 判断结果
   ├─ 权限通过 (返回 true)
   │  └─ 执行原方法：joinPoint.proceed()
   │     └─ 继续处理请求 → 200 OK
   │
   └─ 权限失败 (返回 false)
      └─ 抛出异常：throw new SecurityException(...)
         └─ 返回 403 Forbidden
```

### 权限检查决策树

```
是否有 topicId 属性?
├─ 是 → 检查单个 Topic 权限
│
是否有 topicIds 属性?
├─ 是 → 检查多个 Topic 权限（全部有权限才通过）
│
是否有知识类型和知识ID?
├─ 是 → 检查知识资源权限
│
是否有知识类型和知识ID列表?
├─ 是 → 检查多个知识资源权限
│
都没有 → 返回 false (权限失败)
```

---

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP 请求                                │
│              带 Authorization Header                        │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│          DataSuite Auth ThreadLocal                          │
│     (从请求头提取用户信息)                                   │
│     currentUserEmail = "user@shopee.com"                     │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│         Spring Controller Layer                              │
│    @PermCheck(topicId = "#id", ...)                         │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│              Spring AOP (@Around)                            │
│          PermCheckAspect.checkPermission()                  │
│                                                              │
│  1. 获取当前用户邮箱                                         │
│  2. 解析 SpEL 表达式 → 获取权限检查参数                      │
│  3. 调用权限检查服务                                         │
│  4. 判断权限结果                                             │
└────────────────────────┬────────────────────────────────────┘
                         ↓
        ┌────────────────┴────────────────┐
        ↓                                 ↓
   权限通过                            权限失败
   继续执行                            抛异常
   joinPoint.proceed()                 SecurityException
        ↓                                 ↓
┌─────────────────────────┐  ┌──────────────────────────┐
│  业务逻辑执行           │  │  异常处理器              │
│  Service Layer          │  │  返回 403 Forbidden      │
└─────────────────────────┘  └──────────────────────────┘
        ↓                                 ↓
┌─────────────────────────────────────────────────────────────┐
│                    HTTP 响应                                │
│               200 OK 或 403 Forbidden                       │
└─────────────────────────────────────────────────────────────┘
```

### 分层设计

```
Controller Layer (表现层)
├─ @PermCheck 注解标记权限要求
└─ 处理 HTTP 请求/响应

↓ (Spring AOP 拦截)

AOP Layer (切面层)
├─ PermCheckAspect 切面
├─ SpEL 表达式解析
└─ 权限检查触发

↓

Service Layer (服务层)
├─ PermissionCheckService
├─ 权限检查业务逻辑
├─ RAM API 调用
└─ 数据库查询

↓

Manager Layer (数据访问层)
├─ ChatbiTopicManager
├─ BusinessDocumentManager
├─ BusinessGlossaryManager
└─ BusinessRulesManager

↓

Database (数据库层)
├─ chatbi_topic_tab
├─ business_document_tab
├─ business_glossary_tab
└─ business_rules_tab
```

### 关键技术点

| 技术点 | 实现 | 作用 |
|--------|------|------|
| **Annotation** | @Target(METHOD) @Retention(RUNTIME) | 声明式权限检查 |
| **Spring AOP** | @Aspect @Around | 在方法执行前进行权限验证 |
| **SpEL** | SpelExpressionParser | 动态获取方法参数 |
| **ThreadLocal** | DataSuiteAuthThreadLocal | 跨线程传递用户信息 |
| **Exception** | SecurityException | 权限检查失败异常处理 |
| **Feign** | RamApiClient | 调用外部权限管理 API |

---

## 总结表格

| 方面 | 说明 |
|------|------|
| **实现方式** | 注解 + Spring AOP |
| **切点** | 标注 @PermCheck 的所有方法 |
| **通知类型** | @Around（环绕通知） |
| **参数解析** | SpEL 表达式 + 字面量 |
| **权限维度** | Topic（话题）、Knowledge（知识资源） |
| **权限规则** | OWNER_OR_PROJECT_ADMIN、PROJECT_MEMBER |
| **用户识别** | DataSuiteAuthThreadLocal 获取当前用户 |
| **权限数据源** | RAM API（远程权限管理服务） |
| **异常处理** | SecurityException 抛出 |
| **优势** | 声明式、易扩展、业务逻辑清晰 |

---

## 常见问题

**Q1: 如果权限检查失败会怎样？**
A: AOP 切面会捕获权限检查结果，如果返回 false，则抛出 SecurityException，HTTP 层会返回 403 Forbidden 错误。

**Q2: SpEL 表达式中能否使用复杂表达式？**
A: 可以。例如 `#topicIds.get(0)` 可以获取列表的第一个元素，`#entity.id` 可以获取对象属性。

**Q3: 如何排除某些方法不做权限检查？**
A: 不添加 @PermCheck 注解即可。AOP 只拦截标注了该注解的方法。

**Q4: 权限检查性能会不会很差？**
A: 性能取决于 RAM API 的响应时间。项目中可考虑缓存权限信息来提升性能。

**Q5: 支持多用户并发权限检查吗？**
A: 完全支持。DataSuiteAuthThreadLocal 使用 ThreadLocal，每个线程有独立的用户信息。

