# Coding Style 規範

## Base Package
com.isoprecheck

## 框架
- Spring Boot 版本：3.2.0
- ORM：JPA
- Java 版本：17
- 建構工具：Maven

## 已存在的共用 Class

### ApiResponse<T>
- Package: com.isoprecheck.common
- 說明: 統一回傳格式
- 可用方法（只能用以下這些，不得自行新增）:
  - `ApiResponse.success(T data)` — 成功含資料
  - `ApiResponse.success()` — 成功不含資料
  - `ApiResponse.error(int code, String message)` — 失敗
- 欄位: `int code`, `String message`, `T data`
- 注意: 沒有 `success(String message, T data)` 這個 overload

### BusinessException
- Package: com.isoprecheck.exception
- 建構子: `new BusinessException(String message)`
- 注意: 只接受一個 String 參數

### GlobalExceptionHandler
- Package: com.isoprecheck.exception
- 說明: 全域例外處理，不需要在 Controller 個別處理例外

### 所有已生成的 Entity

#### AuthorizationLog
- Package: com.isoprecheck.entity
- Table name: `authorization_logs`
- 欄位與 getter 方法（完整列出，場景二必須嚴格使用這些方法名稱）:
  | 欄位名稱 | Java 型別 | Column name | Getter 方法 |
  |---|---|---|---|
  | id | Long | id | getId() |
  | maskedPan | String | masked_pan | getMaskedPan() |
  | amountMinor | Long | amount_minor | getAmountMinor() |
  | responseCode | String | response_code | getResponseCode() |
  | createdAt | LocalDateTime | created_at | getCreatedAt() |

## Package 結構
- com.isoprecheck.controller — REST Controller
- com.isoprecheck.service — Service 介面
- com.isoprecheck.service.impl — Service 實作
- com.isoprecheck.repository — Repository
- com.isoprecheck.entity — JPA Entity
- com.isoprecheck.dto — Request/Response DTO
- com.isoprecheck.common — 共用 class
- com.isoprecheck.exception — 例外處理
- com.isoprecheck.rules — 純 Java 檢查規則

## 命名規則
- Controller: XxxController
- Service 介面: XxxService
- Service 實作: XxxServiceImpl
- Repository: XxxRepository
- Entity: Xxx（無後綴）
- Request DTO: XxxRequest
- Response DTO: XxxResponse

## 已生成的 DTO（場景二不得重複生成）
- com.isoprecheck.dto.AuthorizationRequest
- com.isoprecheck.dto.AuthorizationResponse
- com.isoprecheck.dto.AuthorizationLogResponse

## 特殊規則
- 使用 Lombok
- 檢查規則獨立在 com.isoprecheck.rules.AuthorizationRules，不含任何 Spring/Lombok annotation
- PAN 一律以遮罩形式儲存（前6碼+星號+後4碼）
- 金額一律使用 long 最小貨幣單位
- 不可在 log/response 中暴露系統內部例外訊息