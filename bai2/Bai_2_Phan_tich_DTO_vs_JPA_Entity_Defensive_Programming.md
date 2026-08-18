# Bài 2: Phân tích thiết kế dữ liệu bóc tách phòng thủ

## 1. Bối cảnh

Hệ thống CRM cần sử dụng LLM để bóc tách các tin nhắn thô từ tài xế thành dữ liệu có cấu trúc.

Ví dụ tin nhắn:

```text
"Xe 29A-12345 gặp sự cố thủng lốp tại Km 35 cao tốc Hà Nội - Hải Phòng.
Tài xế Nguyễn Văn A đang chờ hỗ trợ."
```

LLM có thể bóc tách thành dữ liệu:

```text
vehicleNumber = "29A-12345"
driverName     = "Nguyễn Văn A"
location       = "Km 35 cao tốc Hà Nội - Hải Phòng"
description    = "Xe bị thủng lốp"
```

Vấn đề thiết kế là dữ liệu do AI trả về nên được ánh xạ trực tiếp vào JPA Entity hay đi qua một DTO trung gian.

Có hai phương án:

- **Phương án 1:** `BeanOutputConverter` → `IncidentReport` → Database.
- **Phương án 2:** `BeanOutputConverter` → `IncidentExtraction` → kiểm tra/mapping nghiệp vụ → `IncidentReport` → Database.

Phương án 2 được lựa chọn vì phù hợp hơn với nguyên tắc **Defensive Programming**.

---

# 2. Phương án 1: Bóc tách trực tiếp vào JPA Entity

Kiến trúc:

```text
Raw Message
     |
     v
    LLM
     |
     v
BeanOutputConverter
     |
     v
IncidentReport
     |
     v
JPA / Hibernate
     |
     v
Database
```

Ví dụ:

```java
IncidentReport report =
        converter.convert(llmResponse);
```

Trong trường hợp này, `IncidentReport` vừa đóng vai trò:

- Domain/Persistence Entity.
- Đối tượng được Hibernate quản lý.
- Đối tượng nhận dữ liệu từ LLM.

## 2.1. Ưu điểm

### Đơn giản

Kiến trúc ít lớp:

```text
LLM -> Entity -> Database
```

Không cần tạo thêm DTO và mapping.

### Ít mã nguồn

Developer không cần viết:

```java
IncidentExtraction -> IncidentReport
```

Do đó phù hợp với prototype hoặc bài toán rất nhỏ.

### Nhanh để triển khai

Khi cấu trúc dữ liệu AI và database gần như giống nhau, cách này có thể giúp triển khai PoC nhanh.

---

# 3. Nhược điểm của phương án 1

Đây là vấn đề quan trọng nhất.

## 3.1. Vi phạm Single Responsibility

`IncidentReport` phải chịu quá nhiều trách nhiệm:

```text
IncidentReport
 ├── Persistence
 ├── Hibernate lifecycle
 ├── Database mapping
 ├── Domain state
 └── LLM extraction target
```

Một JPA Entity nên đại diện cho trạng thái/domain cần persistence, không nên trở thành hợp đồng dữ liệu trực tiếp với LLM.

Nếu prompt hoặc JSON output của LLM thay đổi, Entity có thể phải thay đổi theo.

---

# 4. Rủi ro từ dữ liệu không đáng tin cậy của LLM

LLM không phải nguồn dữ liệu đáng tin cậy tuyệt đối.

Có thể xảy ra:

```json
{
  "vehicleNumber": null,
  "driverName": "",
  "location": null,
  "description": "..."
}
```

Hoặc:

```json
{
  "vehicleNumber": "UNKNOWN",
  "driverName": "Không xác định"
}
```

Hoặc model trả về giá trị không đúng nghiệp vụ.

Nếu dữ liệu được ánh xạ trực tiếp vào Entity:

```text
LLM
 |
 v
Entity
 |
 v
Hibernate
 |
 v
Database
```

thì dữ liệu chưa được kiểm tra đã tiến gần đến tầng persistence.

Đây là một điểm yếu về **Defensive Programming**.

---

# 5. Rủi ro với Hibernate/JPA

JPA Entity có những yêu cầu kỹ thuật riêng.

## 5.1. Constructor mặc định

Một JPA Entity cần có constructor không tham số với visibility phù hợp, thông thường là:

```java
protected IncidentReport() {
}
```

Hibernate sử dụng constructor này trong quá trình khởi tạo Entity.

Trong khi đó, Java Record được thiết kế chủ yếu cho immutable data carrier và không có no-args constructor theo cách JPA Entity yêu cầu.

Điều này khiến Record phù hợp với DTO hơn Entity.

---

# 6. ID auto-generated

Entity thường có:

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

Giá trị `id` được database/Hibernate quản lý.

LLM không nên quyết định:

```json
{
  "id": 123
}
```

Nếu cho phép AI bóc tách trực tiếp vào Entity, có nguy cơ trộn lẫn dữ liệu do AI sinh ra với dữ liệu thuộc quyền kiểm soát của persistence layer.

Với DTO trung gian, `IncidentExtraction` hoàn toàn không cần chứa ID database.

```text
IncidentExtraction
 ├── vehicleNumber
 ├── driverName
 ├── location
 └── description

IncidentReport
 ├── id       <-- Database/Hibernate quản lý
 ├── vehicleNumber
 ├── driverName
 ├── location
 └── description
```

Ranh giới trách nhiệm rõ ràng hơn.

---

# 7. Ràng buộc nullable

Database có thể yêu cầu:

```sql
vehicle_number NOT NULL
```

Nhưng LLM có thể trả:

```json
{
  "vehicleNumber": null
}
```

Nếu sử dụng Entity trực tiếp, quá trình:

```text
LLM
  |
  v
Entity
  |
  v
Repository.save()
  |
  v
Database
```

có thể phát sinh lỗi:

```text
DataIntegrityViolationException
```

hoặc lỗi constraint tương ứng.

Với DTO trung gian:

```text
LLM
 |
 v
IncidentExtraction
 |
 v
Validation
 |
 +---- invalid ----> Reject / Retry / Error Handling
 |
 +---- valid ------> IncidentReport
                         |
                         v
                       DB
```

Ứng dụng có cơ hội kiểm tra dữ liệu trước khi persistence.

---

# 8. Rủi ro khi Entity thay đổi

Giả sử database bổ sung:

```text
createdAt
updatedAt
status
deleted
version
```

Entity:

```text
IncidentReport
```

sẽ phải chứa các field này.

Nhưng LLM không cần biết:

```text
createdAt
updatedAt
deleted
version
```

Nếu dùng Entity làm output target của LLM, contract giữa AI và persistence bị gắn chặt.

DTO giải quyết vấn đề này:

```text
LLM Contract
     |
     v
IncidentExtraction
     |
     v
Business Mapping
     |
     v
IncidentReport
     |
     v
Database
```

LLM chỉ biết những field cần bóc tách.

---

# 9. Phương án 2: DTO trung gian

Kiến trúc:

```text
Raw Driver Message
        |
        v
       LLM
        |
        v
BeanOutputConverter
        |
        v
IncidentExtraction
        |
        v
Validation
        |
        v
Business Mapping
        |
        v
IncidentReport
        |
        v
Hibernate
        |
        v
Database
```

Đây là phương án được lựa chọn.

---

# 10. Ưu điểm của DTO trung gian

## 10.1. Tách biệt AI và Persistence

`IncidentExtraction` đại diện cho:

> Dữ liệu AI bóc tách được.

`IncidentReport` đại diện cho:

> Dữ liệu domain được hệ thống chấp nhận và lưu trữ.

Hai khái niệm này không nên đồng nhất.

---

# 11. Defensive Programming

DTO tạo ra một boundary giữa dữ liệu không đáng tin cậy và hệ thống nội bộ.

```text
             UNTRUSTED
                |
                v
          LLM Output
                |
                v
       IncidentExtraction
                |
        Validate / Sanitize
                |
                v
             TRUSTED
                |
                v
         IncidentReport
                |
                v
            Database
```

Đây là điểm mạnh lớn nhất của phương án 2.

Dữ liệu từ LLM phải được coi là **untrusted input**.

---

# 12. Kiểm tra dữ liệu trước khi mapping

Ví dụ:

```java
if (extraction.vehicleNumber() == null
        || extraction.vehicleNumber().isBlank()) {

    throw new IllegalArgumentException(
            "Vehicle number is required"
    );
}
```

Có thể kiểm tra:

- `vehicleNumber` có tồn tại không.
- `driverName` có hợp lệ không.
- `location` có bị thiếu không.
- `description` có vượt quá độ dài database không.
- Giá trị có nằm trong tập enum hợp lệ không.
- Dữ liệu có chứa giá trị bất thường không.

Sau khi hợp lệ mới mapping:

```java
IncidentReport report = new IncidentReport();

report.setVehicleNumber(extraction.vehicleNumber());
report.setDriverName(extraction.driverName());
report.setLocation(extraction.location());
report.setDescription(extraction.description());
```

---

# 13. Tính đóng gói

Với Entity, các field persistence có thể có:

```java
private Long id;
private LocalDateTime createdAt;
private LocalDateTime updatedAt;
private IncidentStatus status;
```

Đây là những thông tin thuộc lifecycle của domain/entity.

LLM không nên có quyền ghi trực tiếp các field này.

DTO giới hạn dữ liệu mà AI có thể tạo:

```java
public record IncidentExtraction(
        String vehicleNumber,
        String driverName,
        String location,
        String description
) {
}
```

LLM chỉ tạo được những dữ liệu thuộc phạm vi extraction.

---

# 14. Khả năng mở rộng

Giả sử sau này Entity có thêm:

```text
status
priority
assignedStaff
createdAt
updatedAt
version
```

Không cần thay đổi cấu trúc output của LLM.

Có thể xử lý trong service:

```text
IncidentExtraction
        |
        v
Business Rules
        |
        +--> status = NEW
        +--> priority = HIGH
        +--> createdAt = now()
        |
        v
IncidentReport
```

Điều này giúp hệ thống dễ mở rộng hơn.

---

# 15. Khả năng kiểm thử

DTO có thể được test độc lập:

```text
LLM JSON
   |
   v
IncidentExtraction
   |
   v
Validation
```

Entity cũng có thể test riêng:

```text
IncidentReport
   |
   v
Repository
   |
   v
Database
```

Không cần khởi tạo toàn bộ Hibernate chỉ để kiểm tra logic parsing.

Đây là một lợi ích lớn về khả năng bảo trì và testability.

---

# 16. So sánh hai phương án

| Tiêu chí | Entity trực tiếp | DTO trung gian |
|---|---|---|
| Đơn giản | Tốt | Trung bình |
| Ít mã nguồn | Tốt | Trung bình |
| Tách biệt trách nhiệm | Kém | Tốt |
| Defensive Programming | Kém | Rất tốt |
| Kiểm tra dữ liệu trước DB | Hạn chế | Tốt |
| Tách LLM khỏi Hibernate | Kém | Tốt |
| Bảo vệ ID database | Kém | Tốt |
| Xử lý nullable | Hạn chế | Tốt |
| Khả năng mở rộng | Trung bình | Tốt |
| Testability | Trung bình | Tốt |
| Phù hợp Production | Không khuyến nghị | Khuyến nghị |
| Phù hợp Prototype | Tốt | Tốt |

---

# 17. Kết luận lựa chọn

Phương án tối ưu là:

```text
LLM
 |
 v
IncidentExtraction
 |
 v
Validation
 |
 v
Business Mapping
 |
 v
IncidentReport
 |
 v
Repository
 |
 v
Database
```

Lý do chính:

1. Tách biệt AI extraction với persistence.
2. Không cho dữ liệu chưa kiểm chứng đi trực tiếp vào Entity.
3. Bảo vệ ID và các field do Hibernate/database quản lý.
4. Cho phép validation trước khi lưu.
5. Giảm coupling giữa prompt/LLM output và database schema.
6. Dễ kiểm thử.
7. Dễ thay đổi database/entity mà không ảnh hưởng trực tiếp đến AI contract.
8. Phù hợp với nguyên tắc Defensive Programming.

Trong hệ thống production, dữ liệu do LLM sinh ra nên được xem là **untrusted input** giống như dữ liệu từ HTTP request hoặc hệ thống bên ngoài.

Vì vậy, DTO trung gian tạo ra một **validation boundary** rõ ràng trước khi dữ liệu được chuyển thành domain/persistence entity.

---

# 18. Kiến trúc đề xuất

```text
                    Driver Message
                           |
                           v
                      Spring AI
                           |
                           v
                          LLM
                           |
                           v
                  BeanOutputConverter
                           |
                           v
                 IncidentExtraction
                    Java Record DTO
                           |
                           v
                    Validation Layer
                           |
                    +------+------+
                    |             |
                 Invalid         Valid
                    |             |
                    v             v
              Reject/Retry   Mapping Service
                                  |
                                  v
                         IncidentReport
                            JPA Entity
                                  |
                                  v
                         Hibernate / JPA
                                  |
                                  v
                              Database
```

Đây là thiết kế có ranh giới trách nhiệm rõ ràng:

- **LLM:** bóc tách dữ liệu.
- **DTO:** tiếp nhận dữ liệu AI.
- **Validation:** kiểm tra dữ liệu.
- **Mapping/Domain layer:** áp dụng quy tắc nghiệp vụ.
- **Entity:** đại diện dữ liệu persistence.
- **Hibernate/JPA:** quản lý lifecycle và persistence.
- **Database:** lưu trữ dữ liệu cuối cùng.
