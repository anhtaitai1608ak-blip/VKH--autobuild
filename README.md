# VKH Client - AutoBuild (Meteor addon, Minecraft 1.21.11)

Module: **VKH > vkh-auto-build**

## Tinh nang
- Doc schematic ban da load bang **Litematica** (khong can chon vung, dung ngay schematic dang hien).
- Phan tich shulker: shulker dat trong the gioi (tu mo ra doc noi dung) va shulker trong tui do
  (doc truc tiep, dat xuong, lay do, dao lai).
- Tu di chuyen (Baritone) toi cho dung, chon cho dung de co nhieu block nhat trong tam voi.
- Tu dat block, **tu chinh huong** (stairs, slab, log, observer, piston, lo, bien...) bang cach hoi chinh block
  ve huong dat voi goc quay thu nghiem.
- Neu block sai loai hoac sai huong: tu dao di va dat lai (tat bang `fix-wrong-blocks`).
- Thieu vat lieu: tu di lay tu shulker (`auto-restock`).

## Yeu cau (dat vao thu muc mods, dung ban 1.21.11)
Fabric Loader, Fabric API, Meteor Client, Litematica + MaLiLib, Baritone (rieng, vi Meteor khong con kem Baritone).

## Build ra file .jar
Cach 1 (de nhat, khong can cai gi): day thu muc nay len GitHub -> tab Actions -> Build -> tai artifact `VKH-AutoBuild-jar`.
Cach 2: cai JDK 21 + Gradle, chay `gradle build` -> `build/libs/VKH-AutoBuild-1.0.0.jar`.

Neu Gradle bao khong tim thay `fabric-loom`, `yarn_mappings` hay `loader_version`: mo template
https://github.com/MeteorDevelopment/meteor-addon-template (ban 1.21.11) va chep dung 3 gia tri do vao
`build.gradle` / `gradle.properties`. Neu co loi bien dich ten class/phuong thuc, gui loi cho minh de sua.

## Gioi han
- Chi quet trong ban kinh `scan-radius` quanh ban; xay xong vung nay thi di chuyen toi phan tiep theo.
- Block khong co cho tua (lo lung) se cho den khi block ben duoi/canh duoc dat.
- Khong tu tao gian giao; khong xu ly block lo hai nua (cua, giuong) rieng le.
- Nhieu server cam mod tu xay/tu dao. Dung co the bi ban.
