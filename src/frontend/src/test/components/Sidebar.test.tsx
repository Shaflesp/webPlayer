import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Sidebar } from '../../components/Sidebar';
import { useStore } from '../../store';
import * as api from '../../api';

// jsdom's DragEvent/DataTransfer support is incomplete — the component only
// ever writes `dataTransfer.effectAllowed`, so a plain mock object standing
// in for a real DataTransfer is sufficient without needing a fully
// spec-compliant implementation.
function fakeDataTransfer() {
  return { effectAllowed: '', setData: vi.fn(), getData: vi.fn() };
}

const songs = [
  { Id: '1', file: 'a.mp3', Title: 'Song A' },
  { Id: '2', file: 'b.mp3', Title: 'Song B' },
  { Id: '3', file: 'c.mp3', Title: 'Song C' },
];

beforeEach(() => {
  useStore.setState({
    sidebarTab: 'queue',
    queue: songs,
    status: { ...useStore.getState().status, songid: -1 },
  });
  vi.restoreAllMocks();
});

describe('Sidebar — queue drag-reorder', () => {
  it('dragging item 0 onto item 2 calls moveQueue(0, 2)', async () => {
    const moveQueueSpy = vi.spyOn(api, 'moveQueue').mockResolvedValue(undefined);
    render(<Sidebar />);

    const rows = screen.getAllByText(/^Song [ABC]$/).map(el => el.closest('.list-item')!);
    const [rowA, , rowC] = rows;

    fireEvent.dragStart(rowA, { dataTransfer: fakeDataTransfer() });
    fireEvent.dragOver(rowC, { dataTransfer: fakeDataTransfer() });
    fireEvent.drop(rowC, { dataTransfer: fakeDataTransfer() });

    expect(moveQueueSpy).toHaveBeenCalledWith(0, 2);
  });

  it('dropping on the same row that was dragged does not call moveQueue', async () => {
    const moveQueueSpy = vi.spyOn(api, 'moveQueue').mockResolvedValue(undefined);
    render(<Sidebar />);

    const rows = screen.getAllByText(/^Song [ABC]$/).map(el => el.closest('.list-item')!);
    const [rowA] = rows;

    fireEvent.dragStart(rowA, { dataTransfer: fakeDataTransfer() });
    fireEvent.dragOver(rowA, { dataTransfer: fakeDataTransfer() });
    fireEvent.drop(rowA, { dataTransfer: fakeDataTransfer() });

    expect(moveQueueSpy).not.toHaveBeenCalled();
  });

  it('applies the dragging visual class to the row being dragged', () => {
    render(<Sidebar />);
    const rows = screen.getAllByText(/^Song [ABC]$/).map(el => el.closest('.list-item')!);
    const [rowA] = rows;

    fireEvent.dragStart(rowA, { dataTransfer: fakeDataTransfer() });

    expect(rowA.className).toContain('dragging');
  });

  it('applies the drag-over visual class only to the row currently being hovered, not the one being dragged', () => {
    render(<Sidebar />);
    const rows = screen.getAllByText(/^Song [ABC]$/).map(el => el.closest('.list-item')!);
    const [rowA, rowB, rowC] = rows;

    fireEvent.dragStart(rowA, { dataTransfer: fakeDataTransfer() });
    fireEvent.dragOver(rowC, { dataTransfer: fakeDataTransfer() });

    expect(rowC.className).toContain('drag-over');
    expect(rowB.className).not.toContain('drag-over');
    // The dragged row itself is never also marked drag-over, even if hovered
    fireEvent.dragOver(rowA, { dataTransfer: fakeDataTransfer() });
    expect(rowA.className).not.toContain('drag-over');
  });

  it('clears all drag state after dragEnd', () => {
    render(<Sidebar />);
    const rows = screen.getAllByText(/^Song [ABC]$/).map(el => el.closest('.list-item')!);
    const [rowA, , rowC] = rows;

    fireEvent.dragStart(rowA, { dataTransfer: fakeDataTransfer() });
    fireEvent.dragOver(rowC, { dataTransfer: fakeDataTransfer() });
    fireEvent.dragEnd(rowA);

    expect(rowA.className).not.toContain('dragging');
    expect(rowC.className).not.toContain('drag-over');
  });
});
