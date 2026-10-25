# Coding Style 規範

## Base Package
com.demo

## 框架
- Spring Boot 版本：3.2.5
- ORM：JPA
- Java 版本：17
- 建構工具：Maven

## 已存在的共用 Class

### ApiResponse<T>
- Package: com.demo.common
- 說明: 統一回傳格式
- 可用方法（只能用以下這些，不得自行新增）:
  - `ApiResponse.success(T data)` — 成功含資料
  - `ApiResponse.success()` — 成功不含資料
  - `ApiResponse.error(int code, String message)` — 失敗
- 欄位: `int code`, `String message`, `T data`
- 注意: 沒有 `success(String message, T data)` 這個 overload

### BusinessException
- Package: com.demo.exception
- 建構子: `new BusinessException(String message)`
- 注意: 只接受一個 String 參數

### GlobalExceptionHandler
- Package: com.demo.exception
- 說明: 全域例外處理，不需要在 Controller 個別處理例外

### 所有已生成的 Entity

#### Product
- Package: com.demo.entity
- Table name: `products`
- 欄位與 getter 方法（完整列出，場景二必須嚴格使用這些方法名稱）:
  | 欄位名稱 | Java 型別 | Column name | Getter 方法 |
  |---|---|---|---|
  | productId | Long | product_id | getProductId() |
  | name | String | name | getName() |
  | price | BigDecimal | price | getPrice() |

## Package 結構
- com.demo.controller — REST Controller
- com.demo.service — Service 介面
- com.demo.service.impl — Service 實作
- com.demo.repository — Repository
- com.demo.entity — JPA Entity
- com.demo.dto — Request/Response DTO
- com.demo.common — 共用 class
- com.demo.exception — 例外處理

## 命名規則
- Controller: XxxController
- Service 介面: XxxService
- Service 實作: XxxServiceImpl
- Repository: XxxRepository
- Entity: Xxx（無後綴）
- Request DTO: XxxRequest
- Response DTO: XxxResponse

## 已生成的 DTO（場景二不得重複生成）
- `ProductResponse` — com.demo.dto

## 特殊規則
- 使用 Lombok（@Getter、@Setter、@Builder、@NoArgsConstructor、@AllArgsConstructor、@RequiredArgsConstructor、@Slf4j）
- Service 查詢方法使用 @Transactional(readOnly = true)
- Entity 轉 DTO 在 ServiceImpl 內以私有方法處理
- 不使用分頁，回傳完整清單
- 所有 Controller 回傳統一用 ApiResponse<T> 包裝
- GlobalExceptionHandler 已統一處理例外，Controller 不需要 try-catch