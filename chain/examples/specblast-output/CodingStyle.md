# Coding Style 規範

## Base Package
com.card

## 框架
- Spring Boot 版本：3.2.5
- ORM：JPA
- Java 版本：17
- 建構工具：Maven

## 已存在的共用 Class

### ApiResponse<T>
- Package: com.card.common
- 說明: 統一回傳格式
- 可用方法（只能用以下這些，不得自行新增）:
  - `ApiResponse.success(T data)` — 成功含資料
  - `ApiResponse.success()` — 成功不含資料
  - `ApiResponse.error(int code, String message)` — 失敗
- 欄位: `int code`, `String message`, `T data`
- 注意: 沒有 `success(String message, T data)` 這個 overload

### BusinessException
- Package: com.card.exception
- 建構子: `new BusinessException(String message)`
- 注意: 只接受一個 String 參數

### GlobalExceptionHandler
- Package: com.card.exception
- 說明: 全域例外處理，不需要在 Controller 個別處理例外

### 所有已生成的 Entity

此服務為無狀態純規則引擎，當前版本無 Entity。

## Package 結構
- com.card.controller — REST Controller
- com.card.service — Service 介面
- com.card.service.impl — Service 實作
- com.card.rules — 授權規則引擎（純 Java，無 Spring/Lombok）
- com.card.dto — Request/Response DTO
- com.card.common — 共用 class
- com.card.exception — 例外處理
- com.card.config — Spring Bean 設定

## 命名規則
- Controller: XxxController
- Service 介面: XxxService
- Service 實作: XxxServiceImpl
- Repository: XxxRepository
- Entity: Xxx（無後綴）
- Request DTO: XxxRequest
- Response DTO: XxxResponse

## 已生成的 DTO（場景二不得重複生成）
- com.card.dto.AuthorizationRequest
- com.card.dto.AuthorizationResponse

## 特殊規則
- 使用 Lombok（@Getter、@Setter、@NoArgsConstructor、@AllArgsConstructor）
- 使用繁體中文 JavaDoc 註解
- AuthorizationRules 類別不得包含 Spring、Lombok、資料庫或 Log 相關程式碼（規格 Verification contract 要求）
- AuthorizationRulesConfig 透過 @Bean 將 AuthorizationRules 納入 Spring IoC
- Service 實作委派 AuthorizationRules.check()，不重複業務規則邏輯
- PCI DSS NF-01：所有層級（Controller、Service、Log）嚴格禁止輸出 PAN
- 規則評估順序：AC-01 → AC-02 → AC-03 → AC-04 → AC-05 → AC-06
- AC-03 過期判斷以到期月份最後一天為基準（當天仍有效）
- AC-05 等於餘額允許交易
- Luhn 演算法由 AuthorizationRules 內部 passesLuhn() private 方法實作
- 全形數字（如 ４）不符合 `^[0-9]{13,19}$` 正規表示式，正確回傳 AC-02