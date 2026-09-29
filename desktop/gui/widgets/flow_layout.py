"""自适应宽度换行布局。"""
from __future__ import annotations

from PySide6.QtCore import QPoint, QRect, QSize, Qt
from PySide6.QtWidgets import QLayout, QLayoutItem, QSizePolicy, QWidget


def _item_size(item: QLayoutItem) -> QSize:
    return item.sizeHint().expandedTo(item.minimumSize())


class FlowLayout(QLayout):
    def __init__(self, parent: QWidget | None = None, margin: int = 0, h_spacing: int = 10, v_spacing: int = 8) -> None:
        super().__init__(parent)
        self._items: list[QLayoutItem] = []
        self._h_spacing = h_spacing
        self._v_spacing = v_spacing
        self.setContentsMargins(margin, margin, margin, margin)

    def addItem(self, item: QLayoutItem) -> None:
        self._items.append(item)

    def addWidget(self, widget: QWidget) -> None:
        from PySide6.QtWidgets import QWidgetItem

        self.addItem(QWidgetItem(widget))

    def count(self) -> int:
        return len(self._items)

    def itemAt(self, index: int) -> QLayoutItem | None:
        if 0 <= index < len(self._items):
            return self._items[index]
        return None

    def takeAt(self, index: int) -> QLayoutItem | None:
        if 0 <= index < len(self._items):
            return self._items.pop(index)
        return None

    def expandingDirections(self) -> Qt.Orientation:
        return Qt.Orientation(0)

    def hasHeightForWidth(self) -> bool:
        return True

    def heightForWidth(self, width: int) -> int:
        return self._do_layout(QRect(0, 0, width, 0), test_only=True)

    def setGeometry(self, rect: QRect) -> None:
        super().setGeometry(rect)
        self._do_layout(rect, test_only=False)

    def sizeHint(self) -> QSize:
        return self._wrapped_size(self._reference_width())

    def minimumSize(self) -> QSize:
        return self._wrapped_size(self._reference_width())

    def _reference_width(self) -> int:
        parent = self.parentWidget()
        if parent is not None and parent.width() > 0:
            return parent.width()
        return 640

    def _wrapped_size(self, width: int) -> QSize:
        margins = self.contentsMargins()
        if not self._items:
            return QSize(0, 0)
        max_item_w = max(_item_size(item).width() for item in self._items)
        ref_w = max(width, max_item_w + margins.left() + margins.right())
        height = self.heightForWidth(ref_w)
        return QSize(ref_w, height)

    def _do_layout(self, rect: QRect, *, test_only: bool) -> int:
        margins = self.contentsMargins()
        x = rect.x() + margins.left()
        y = rect.y() + margins.top()
        line_height = 0
        max_width = rect.width() - margins.left() - margins.right()

        for item in self._items:
            widget = item.widget()
            if widget and widget.isHidden():
                continue
            hint = _item_size(item)
            next_x = x + hint.width() + self._h_spacing
            if next_x - self._h_spacing > rect.x() + margins.left() + max_width and line_height > 0:
                x = rect.x() + margins.left()
                y += line_height + self._v_spacing
                next_x = x + hint.width() + self._h_spacing
                line_height = 0
            if not test_only:
                item.setGeometry(QRect(QPoint(x, y), hint))
            x = next_x
            line_height = max(line_height, hint.height())

        return y + line_height - rect.y() + margins.bottom()


class FlowContainer(QWidget):
    """承载 FlowLayout 的容器，正确上报换行后的高度。"""

    def __init__(
        self,
        parent: QWidget | None = None,
        *,
        margin: int = 0,
        h_spacing: int = 10,
        v_spacing: int = 8,
    ) -> None:
        super().__init__(parent)
        self._flow = FlowLayout(self, margin=margin, h_spacing=h_spacing, v_spacing=v_spacing)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)

    def flow_layout(self) -> FlowLayout:
        return self._flow

    def addWidget(self, widget: QWidget) -> None:
        self._flow.addWidget(widget)

    def hasHeightForWidth(self) -> bool:
        return True

    def heightForWidth(self, width: int) -> int:
        return self._flow.heightForWidth(width)

    def sizeHint(self) -> QSize:
        width = self.width() if self.width() > 0 else 640
        return QSize(width, self.heightForWidth(width))

    def minimumSizeHint(self) -> QSize:
        return self.sizeHint()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.setMinimumHeight(self.heightForWidth(max(self.width(), 1)))