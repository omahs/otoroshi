import { useEffect, useState } from 'react';

export default function Slider({ setSidebarStatus, closedSidebar }) {
  const [mousePos, setMousePos] = useState({});
  const [show, setShow] = useState(false);

  useEffect(() => {
    const handleMouseMove = (event) => {
      setMousePos({ x: event.clientX, y: event.clientY });
    };

    window.addEventListener('mousemove', handleMouseMove);

    return () => {
      window.removeEventListener(
        'mousemove',
        handleMouseMove
      );
    };
  }, []);

  if (mousePos.x === undefined)
    return

  return <div className='sidebar flex'
    style={{
      background: '#ddd',
      bottom: 0,
      top: 0,
      width: 3,
      left: 'auto',
      position: 'absolute',
      right: 0,
    }}
    onClick={setSidebarStatus}
    onMouseEnter={() => setShow(true)}
    onMouseLeave={() => setShow(false)}
  >
    {show && <div style={{
      flex: 1,
      position: 'relative'
    }}>
      <div className='d-flex' style={{
        background: '#eee',
        alignItems: 'center',
        justifyContent: 'center',
        width: 20,
        height: 20,
        borderRadius: '50%',

        position: 'absolute',
        left: mousePos.x - (closedSidebar ? 42 : 250) - 3,
        top: mousePos.y - 12
      }}>
        <i className={`fa-solid fa-chevron-${closedSidebar ? 'right' : 'left'}`}>
        </i>
      </div>
    </div>}
  </div>
}